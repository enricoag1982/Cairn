package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.EFFECTIVE_TIMES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyValueStores;
import io.github.enricoag1982.cairn.core.storage.StoredMetadata;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scenarios of the {@code durability} spec that concern RocksDB stores, one test each. */
class RocksDbStoresScenariosTest {

    @TempDir
    Path directory;

    private RocksDbFixture rocks;

    @BeforeEach
    void setUp() {
        rocks = new RocksDbFixture(directory);
    }

    @AfterEach
    void tearDown() {
        rocks.closeAll();
    }

    private static StoreEquivalence.Observation observe(Store store, ReferenceTales.Told told) {
        return StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, List.of());
    }

    @Test
    @DisplayName("Scenario: Reopening a RocksDB store")
    void reopeningARocksDbStore() {
        DurableStore first = rocks.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, rocks.clock);
        StoreEquivalence.Observation before = observe(first, told);
        Basis latest = first.latestBasis();
        first.close();

        DurableStore reopened = rocks.open();

        StoreEquivalence.assertEquivalent(before, observe(reopened, told));
        assertThat(reopened.schema().history()).as("the schema's history").hasSize(1);
        assertThat(reopened.latestBasis()).isEqualTo(latest);
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 15))).entities("Party")).as("a view reads").hasSize(3);
        Receipt next = RocksDbFixture.commit(reopened,
                Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob")).build());
        assertThat(next.basis()).as("the next commit gets the next basis").isEqualTo(new Basis(latest.value() + 1));
    }

    @Test
    @DisplayName("Scenario: A RocksDB store after a crash")
    void aRocksDbStoreAfterACrash() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", testRuntimeClasspath(),
                CrashedProcess.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the process stopped by itself").isTrue();
        assertThat(process.exitValue()).as("the process halted after its commits returned")
                .isEqualTo(CrashedProcess.HALTED);
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).build());
        RocksDbFixture.registerReferenceModel(memory);
        List<Receipt> receipts = CrashedProcess.tell(memory, memoryClock);
        List<Id> ids = receipts.stream().flatMap(receipt -> receipt.created().stream()).toList();

        DurableStore reopened = rocks.open();

        assertThat(reopened.transactions()).as("every transaction").hasSize(3);
        assertThat(reopened.latestBasis()).isEqualTo(memory.latestBasis());
        StoreEquivalence.assertEquivalent(StoreEquivalence.observe(memory, ids, EFFECTIVE_TIMES, List.of()),
                StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES, List.of()));
    }

    /** The class path of the tests, which the build passes; the class path of this JVM if it is run outside the build. */
    private static String testRuntimeClasspath() {
        return System.getProperty("cairn.testRuntimeClasspath", System.getProperty("java.class.path"));
    }

    @Test
    @DisplayName("Scenario: The same results as an in-memory store")
    void theSameResultsAsAnInMemoryStore() {
        for (long seed = 1; seed <= 8; seed++) {
            runWithSeed(seed);
        }
        assertThat(done).as("the seeds make commits, registrations, purges, and reopen the store")
                .containsOnlyKeys("commit", "registration", "purge", "reopen", "rejection")
                .allSatisfy((what, count) -> assertThat(count).as(what).isGreaterThan(5));
    }

    /** How many operations of each kind the seeds made. */
    private final Map<String, Integer> done = new TreeMap<>();

    private void did(String what) {
        done.merge(what, 1, Integer::sum);
    }

    private static SchemaDefinition extras(int attributes) {
        return SchemaDefinition.builder().entityType("Extra", extra -> IntStream.rangeClosed(1, attributes)
                .forEach(i -> extra.attribute("a" + i, ValueType.STRING))).build();
    }

    /**
     * Commits random transactions, schema registrations and purges to a RocksDB store and to an in-memory store, closing
     * and opening the first now and then, and compares them: the receipts and problems of every operation, and every view at
     * every basis. The commits that create people carry idempotency keys, which the stores are asked to commit again at
     * every comparison, so that the keys that purges left unusable are compared too.
     */
    private void runWithSeed(long seed) {
        RocksDbFixture fixture = new RocksDbFixture(directory.resolve("run-" + seed));
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules()).build());
        DurableStore store = fixture.open();
        RocksDbFixture.registerReferenceModel(memory);
        RocksDbFixture.registerReferenceModel(store);
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
                    did("commit");
                    Receipt expected = RocksDbFixture.commit(memory, create);
                    assertThat(RocksDbFixture.commit(store, create)).as(where).isEqualTo(expected);
                    people.add(expected.created().getFirst());
                    keyed.add(create);
                }
                case 3, 4 -> {
                    List<Id> live = people.stream().filter(id -> !ended.contains(id) && !purged.contains(id))
                            .toList();
                    if (!live.isEmpty()) {
                        Id person = live.get(random.nextInt(live.size()));
                        Transaction patch = Transaction.by("importer").reason("rename " + step)
                                .patch((EntityId) person, from(2, 1), values("name", "Renamed " + step)).build();
                        assertThat(RocksDbFixture.commit(store, patch)).as(where)
                                .isEqualTo(RocksDbFixture.commit(memory, patch));
                    }
                }
                case 5 -> {
                    List<Id> live = people.stream().filter(id -> !ended.contains(id) && !purged.contains(id))
                            .toList();
                    if (!live.isEmpty()) {
                        Id person = live.get(random.nextInt(live.size()));
                        Transaction end = Transaction.by("importer").endDate((EntityId) person, on(9, 1)).build();
                        assertThat(RocksDbFixture.commit(store, end)).as(where)
                                .isEqualTo(RocksDbFixture.commit(memory, end));
                        ended.add(person);
                    }
                }
                case 6 -> {
                    extraAttributes++;
                    did("registration");
                    Origin origin = new Origin("extras", "1." + extraAttributes);
                    assertThat(memory.schema().register(origin, extras(extraAttributes)))
                            .isInstanceOf(RegistrationResult.Applied.class);
                    assertThat(store.schema().register(origin, extras(extraAttributes)))
                            .isInstanceOf(RegistrationResult.Applied.class);
                }
                case 7 -> store.writeSnapshot();
                case 10 -> {
                    did("rejection");
                    Transaction invalid = Transaction.by("importer")
                            .create("Person", from(1, 1), values("name", step)).build();
                    CommitResult expected = memory.commit(invalid);
                    assertThat(expected).as(where).isInstanceOf(CommitResult.Rejected.class);
                    assertThat(store.commit(invalid)).as("the problems of the rejection, " + where).isEqualTo(expected);
                }
                case 8 -> {
                    did("reopen");
                    store.close();
                    store = fixture.open();
                    assertEquivalent(memory, store, people, keyed, where + " after close");
                }
                default -> {
                    List<Id> candidates = people.stream().filter(id -> !purged.contains(id)).toList();
                    if (!candidates.isEmpty()) {
                        Id person = candidates.get(random.nextInt(candidates.size()));
                        Purge purge = Purge.by("dpo").reason("erasure " + step).entity((EntityId) person).build();
                        did("purge");
                        PurgeResult expected = memory.purge(purge);
                        assertThat(expected).as(where).isInstanceOf(PurgeResult.Purged.class);
                        assertThat(store.purge(purge)).as(where).isEqualTo(expected);
                        purged.add(person);
                    }
                }
            }
        }
        assertEquivalent(memory, store, people, keyed, "seed " + seed + ", at the end");
        store.close();
        fixture.closeAll();
    }

    private static final List<Instant> RANDOM_EFFECTIVE_TIMES = List.of(on(1, 1), on(2, 1), on(9, 1), on(12, 1));

    private static void assertEquivalent(Store memory, Store store, List<Id> people, List<Transaction> keyed,
            String where) {
        StoreEquivalence.assertEquivalent(
                StoreEquivalence.observeSampled(memory, people, RANDOM_EFFECTIVE_TIMES, keyed),
                StoreEquivalence.observeSampled(store, people, RANDOM_EFFECTIVE_TIMES, keyed));
        assertThat(store.latestBasis()).as(where).isEqualTo(memory.latestBasis());
    }

    @Test
    @DisplayName("Scenario: A RocksDB directory in use")
    void aRocksDbDirectoryInUse() {
        DurableStore first = RocksDbStores.open(directory, StoreSettings.builder().clock(rocks.clock).build());

        assertThatThrownBy(() -> RocksDbStores.open(directory, StoreSettings.builder().clock(rocks.clock).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(directory.toString());
        first.close();

        DurableStore second = RocksDbStores.open(directory, StoreSettings.builder().clock(rocks.clock).build());
        assertThat(second.latestBasis()).isEqualTo(Basis.NONE);
        second.close();
    }

    @Test
    @DisplayName("Scenario: An unknown format version of a RocksDB store")
    void anUnknownFormatVersionOfARocksDbStore() {
        DurableStore first = rocks.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(first, rocks.clock);
        first.close();
        try (RocksDbKeyValueStore database = RocksDbKeyValueStore.open(directory.resolve(RocksDbStores.DATABASE),
                () -> { })) {
            Batch other = new Batch();
            StoredMetadata.putFormatVersion(other, 3);
            database.apply(other);
        }

        assertThatThrownBy(() -> rocks.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 3").hasMessageContaining(directory.toString());

        KeyValueStores.lockDirectory(directory).run();
    }

    @Test
    @DisplayName("Scenario: A directory of the other kind of store")
    void aDirectoryOfTheOtherKindOfStore() throws IOException {
        Path keptThroughALog = directory.resolve("kept-through-a-log");
        Path keptInRocksDb = directory.resolve("kept-in-rocksdb");
        StoreSettings settings = StoreSettings.builder().clock(rocks.clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build();
        try (DurableStore store = Stores.durable(keptThroughALog, settings)) {
            RocksDbFixture.registerReferenceModel(store);
            ReferenceStories.replay(store, rocks.clock, ReferenceStories.rd1Parties());
        }
        RocksDbFixture other = new RocksDbFixture(keptInRocksDb);
        DurableStore rocksStore = other.openWithReferenceModel();
        other.replay(rocksStore, ReferenceStories.rd1Parties());
        rocksStore.close();
        Map<String, byte[]> logFiles = StoreFiles.contents(keptThroughALog);
        Map<String, byte[]> rocksFiles = StoreFiles.contents(keptInRocksDb);

        assertThatThrownBy(() -> RocksDbStores.open(keptThroughALog, settings)).as("a RocksDB store on a directory store")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(keptThroughALog.toString())
                .hasMessageContaining("Stores.durable");
        assertThatThrownBy(() -> Stores.durable(keptInRocksDb, settings)).as("a directory store on a RocksDB store")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(keptInRocksDb.toString())
                .hasMessageContaining("RocksDbStores.open");

        assertThat(keptThroughALog.resolve(RocksDbStores.DATABASE)).as("no database was created").doesNotExist();
        assertThat(keptInRocksDb.resolve("log")).as("no log was created").doesNotExist();
        assertThat(keptInRocksDb.resolve("snapshots")).as("no snapshot files").doesNotExist();
        assertThat(StoreFiles.contents(keptThroughALog)).as("the files of the directory store")
                .containsOnlyKeys(logFiles.keySet()).allSatisfy((name, bytes) -> assertThat(bytes).as(name)
                        .isEqualTo(logFiles.get(name)));
        assertThat(StoreFiles.contents(keptInRocksDb)).as("the files of the RocksDB store")
                .containsOnlyKeys(rocksFiles.keySet()).allSatisfy((name, bytes) -> assertThat(bytes).as(name)
                        .isEqualTo(rocksFiles.get(name)));
        try (DurableStore again = Stores.durable(keptThroughALog, settings)) {
            assertThat(again.transactions()).as("the directory store, with RD-1").hasSize(1);
            assertThat(again.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).hasSize(1);
        }
        DurableStore reopened = other.open();
        assertThat(reopened.transactions()).as("the RocksDB store, with RD-1").hasSize(1);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).hasSize(1);
        other.closeAll();
    }
}
