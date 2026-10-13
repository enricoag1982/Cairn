package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A durable store and an in-memory store that get the same random operations stay equivalent, whatever happens to the
 * durable store in between: commits, writes to the schema, purges, snapshot files, closing, and crashes, with small log
 * segments so that the log has many of them. The commits that create people carry idempotency keys, which the stores are
 * asked to commit again at every comparison, so that the keys that purges left unusable are compared too.
 */
class DurableRandomizedTest {

    private static final List<Instant> EFFECTIVE_TIMES = List.of(on(1, 1), on(2, 1), on(9, 1), on(12, 1));

    @TempDir
    Path directory;

    private static SchemaDefinition extras(int attributes) {
        return SchemaDefinition.builder().entityType("Extra", extra -> IntStream.rangeClosed(1, attributes)
                .forEach(i -> extra.attribute("a" + i, ValueType.STRING))).build();
    }

    @Test
    void randomOperationsKeepADurableStoreEquivalentToAnInMemoryStore() {
        for (long seed = 1; seed <= 12; seed++) {
            runWithSeed(seed);
        }
    }

    private void runWithSeed(long seed) {
        Path run = directory.resolve("run-" + seed);
        DurableFixture fixture = new DurableFixture(run);
        fixture.segmentLimit = 1_500;
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules()).build());
        DirectoryStore store = fixture.open();
        DurableFixture.registerReferenceModel(memory);
        DurableFixture.registerReferenceModel(store);
        Random random = new Random(seed);
        List<Id> people = new ArrayList<>();
        List<Id> ended = new ArrayList<>();
        List<Id> purged = new ArrayList<>();
        List<Transaction> keyed = new ArrayList<>();
        int extraAttributes = 0;
        for (int step = 0; step < 60; step++) {
            String where = "seed " + seed + ", step " + step;
            switch (random.nextInt(11)) {
                case 0, 1, 2 -> {
                    Transaction create = Transaction.by("importer").idempotencyKey("create-" + step)
                            .create("Person", from(1, 1), values("name", "Person " + step)).build();
                    Receipt expected = DurableFixture.commit(memory, create);
                    assertThat(DurableFixture.commit(store, create)).as(where).isEqualTo(expected);
                    people.add(expected.created().getFirst());
                    keyed.add(create);
                }
                case 3, 4 -> {
                    List<Id> live = people.stream().filter(id -> !ended.contains(id) && !purged.contains(id))
                            .toList();
                    if (!live.isEmpty()) {
                        Id person = live.get(random.nextInt(live.size()));
                        Transaction patch = Transaction.by("importer").reason("rename " + step)
                                .patch((EntityId) person, from(2, 1),
                                        values("name", "Renamed " + step)).build();
                        assertThat(DurableFixture.commit(store, patch)).as(where)
                                .isEqualTo(DurableFixture.commit(memory, patch));
                    }
                }
                case 5 -> {
                    List<Id> live = people.stream().filter(id -> !ended.contains(id) && !purged.contains(id))
                            .toList();
                    if (!live.isEmpty()) {
                        Id person = live.get(random.nextInt(live.size()));
                        Transaction end = Transaction.by("importer").endDate(
                                (EntityId) person, on(9, 1)).build();
                        assertThat(DurableFixture.commit(store, end)).as(where)
                                .isEqualTo(DurableFixture.commit(memory, end));
                        ended.add(person);
                    }
                }
                case 6 -> {
                    extraAttributes++;
                    Origin origin = new Origin("extras", "1." + extraAttributes);
                    assertThat(memory.schema().register(origin, extras(extraAttributes)))
                            .isInstanceOf(RegistrationResult.Applied.class);
                    assertThat(store.schema().register(origin, extras(extraAttributes)))
                            .isInstanceOf(RegistrationResult.Applied.class);
                }
                case 7 -> store.writeSnapshot();
                case 8 -> {
                    store.close();
                    store = fixture.open();
                    assertEquivalent(memory, store, people, keyed, where + " after close");
                }
                case 9 -> {
                    List<Id> candidates = people.stream().filter(id -> !purged.contains(id)).toList();
                    if (!candidates.isEmpty()) {
                        Id person = candidates.get(random.nextInt(candidates.size()));
                        Purge purge = Purge.by("dpo").reason("erasure " + step).entity((EntityId) person).build();
                        PurgeResult expected = memory.purge(purge);
                        assertThat(expected).as(where).isInstanceOf(PurgeResult.Purged.class);
                        assertThat(store.purge(purge)).as(where).isEqualTo(expected);
                        purged.add(person);
                    }
                }
                default -> {
                    DurableFixture.crash(store);
                    store = fixture.open();
                    assertEquivalent(memory, store, people, keyed, where + " after a crash");
                }
            }
        }
        assertEquivalent(memory, store, people, keyed, "seed " + seed + ", at the end");
        store.close();
        fixture.closeAll();
    }

    private static void assertEquivalent(Store memory, Store store, List<Id> people, List<Transaction> keyed,
            String where) {
        StoreEquivalence.assertEquivalent(
                StoreEquivalence.observeSampled(memory, people, EFFECTIVE_TIMES, keyed),
                StoreEquivalence.observeSampled(store, people, EFFECTIVE_TIMES, keyed));
        assertThat(store.latestBasis()).as(where).isEqualTo(memory.latestBasis());
    }
}
