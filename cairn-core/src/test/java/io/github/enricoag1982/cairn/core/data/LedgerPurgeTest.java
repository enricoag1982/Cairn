package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.core.kv.RecordingKeyValueStore;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a purge deletes from the keys (ADR-0024, ADR-0027, design D5 of {@code refactor-storage-onto-key-value-store}):
 * the current keys, rows and markers of what it removes, the index keys of those entities and relationships at both
 * ends, and the unique keys of every value they ever held, so that no key and no value mentions a purged ID or a purged
 * unique value, and nothing that belongs to what remains is touched.
 */
class LedgerPurgeTest {

    private static final HexFormat HEX = HexFormat.of();

    private final RecordingKeyValueStore keys = new RecordingKeyValueStore();
    private final TestClock clock = new TestClock();
    private InMemoryStore store;
    private Schema schema;

    @BeforeEach
    void aStoreOnARecordingKeyValueStoreWithTheReferenceModel() {
        store = new InMemoryStore(keys, clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        schema = store.schema().current();
    }

    /** Every key with its value, as text, in the order of the keys. */
    static Map<String, String> entriesOf(KeyValueStore store) {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var all = store.scan(KeyRange.all())) {
            all.forEach(entry -> entries.put(HEX.formatHex(entry.key()), HEX.formatHex(entry.value())));
        }
        return entries;
    }

    /** Tells whether the text, in UTF-8, is anywhere in a key or a value. */
    static boolean mentions(Map<String, String> entries, String text) {
        String needle = HEX.formatHex(text.getBytes(StandardCharsets.UTF_8));
        return entries.entrySet().stream().anyMatch(entry -> contains(entry.getKey(), needle)
                || contains(entry.getValue(), needle));
    }

    /** Hex text of whole bytes holds the hex text of another run of bytes only if they line up on a byte. */
    private static boolean contains(String hexText, String needle) {
        int at = hexText.indexOf(needle);
        while (at >= 0) {
            if (at % 2 == 0) {
                return true;
            }
            at = hexText.indexOf(needle, at + 1);
        }
        return false;
    }

    /** The ID that a key is about: the entity or relationship whose data it is, or whose index entry it is. */
    private static List<String> idsOf(String keyHex) {
        byte[] key = HEX.parseHex(keyHex);
        return switch (LedgerCodec.kindOf(key)) {
            case CURRENT -> List.of(LedgerCodec.idOfCurrent(key));
            case ROW, SUPERSEDED -> List.of(LedgerCodec.parseRowKey(key).id());
            case TYPE -> List.of(LedgerCodec.entityOfTypeKey(key));
            case END -> List.of(LedgerCodec.parseEndKey(key).relationship());
            case UNIQUE -> List.of(LedgerCodec.idOfUniqueKey(key));
            case CHANGE -> List.of(LedgerCodec.parseChangeKey(key).id());
        };
    }

    private PurgeResult.Purged purge(EntityId... entities) {
        Purge.Builder purge = Purge.by("dpo").reason("DSR-2026-0042");
        for (EntityId entity : entities) {
            purge.entity(entity);
        }
        PurgeResult result = store.purge(purge.build());
        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        return (PurgeResult.Purged) result;
    }

    private AttributeId attribute(String type, String name) {
        return schema.entityType(type).orElseThrow().attribute(name).orElseThrow().id();
    }

    private Receipt commit(Instant recordedAt, Transaction transaction) {
        clock.moveTo(recordedAt);
        CommitResult result = store.commit(transaction);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    @Test
    void purgingAnEntityDeletesExactlyTheKeysOfItAndOfTheRelationshipsItLeavesWithIt() {
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, clock);
        EntityId alice = ReferenceTales.entity(told.parties, ALICE);
        EntityId beta = ReferenceTales.entity(told.parties, BETA);
        RelationshipId employs = ReferenceTales.relationship(told.employment, BETA_EMPLOYS_ALICE);
        Map<String, String> before = entriesOf(keys);
        assertThat(mentions(before, alice.value())).as("Alice is in the keys before").isTrue();
        assertThat(mentions(before, employs.value())).isTrue();

        PurgeResult.Purged purged = purge(alice);

        assertThat(purged.relationships()).containsExactly(employs);
        Map<String, String> after = entriesOf(keys);
        assertThat(mentions(after, alice.value())).as("no key or value mentions Alice").isFalse();
        assertThat(mentions(after, employs.value())).as("or her employment").isFalse();
        assertThat(mentions(after, "Alice")).as("or her name").isFalse();
        Map<String, String> removed = new LinkedHashMap<>(before);
        after.forEach((key, value) -> {
            assertThat(before).as("a purge adds nothing").containsEntry(key, value);
            removed.remove(key);
        });
        assertThat(removed.keySet()).as("a purge removes only the keys of what it purges")
                .allSatisfy(key -> assertThat(idsOf(key)).containsAnyOf(alice.value(), employs.value()));
        assertThat(removed.keySet().stream().map(key -> LedgerCodec.kindOf(HEX.parseHex(key))).distinct())
                .as("of every kind that Alice and her employment have")
                .containsExactlyInAnyOrder(LedgerCodec.Kind.CURRENT, LedgerCodec.Kind.ROW, LedgerCodec.Kind.TYPE,
                        LedgerCodec.Kind.END, LedgerCodec.Kind.CHANGE);
        assertThat(after).as("Beta's end of the employment is gone").doesNotContainKey(HEX.formatHex(
                LedgerCodec.endKey(beta.value(), schema.relationshipType("Employs").orElseThrow().id(), true,
                        employs.value())));
        assertThat(store.ledger().entity(alice)).isEmpty();
        assertThat(store.ledger().entity(beta)).isPresent();
        assertThat(store.ledger().ends(beta, Ledger.LATEST)).extracting(Ledger.End::relationship)
                .doesNotContain(employs).isNotEmpty();
    }

    @Test
    void purgingAnEntityDeletesTheUniqueKeysOfEveryValueItEverHeld() {
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, clock);
        EntityId a1 = ReferenceTales.entity(told.accounts, A1);
        commit(on(7, 1), Transaction.by(PRINCIPAL).patch(a1, from(7, 1), values("number", "A-9")).build());
        commit(on(8, 1), Transaction.by(PRINCIPAL).patch(a1, from(8, 1), values("number", "A-7")).build());
        commit(on(9, 1), Transaction.by(PRINCIPAL).patch(a1, from(9, 1), values("number", "A-1")).build());
        AttributeId number = attribute("Account", "number");
        assertThat(store.ledger().uniqueIds(number, Value.of("A-9"), Ledger.LATEST)).containsExactly(a1.value());
        Map<String, String> before = entriesOf(keys);
        assertThat(mentions(before, "A-9")).isTrue();

        purge(a1);

        Map<String, String> after = entriesOf(keys);
        for (String purged : List.of(a1.value(), "A-1", "A-9", "A-7")) {
            assertThat(mentions(after, purged)).as("no key or value mentions %s", purged).isFalse();
        }
        assertThat(store.ledger().uniqueIds(number, Value.of("A-9"), Ledger.LATEST)).isEmpty();
        assertThat(store.ledger().uniqueIds(number, Value.of("A-1"), Ledger.LATEST)).isEmpty();
        assertThat(mentions(after, "A-2")).as("the other account keeps its number").isTrue();
        assertThat(store.ledger().uniqueIds(number, Value.of("A-2"), Ledger.LATEST)).hasSize(1);
    }

    @Test
    void aPurgedLeiLeavesNoKeyAndCanBeTakenByAnotherOrganization() {
        TemporaryId delta = new TemporaryId("delta");
        Receipt created = commit(on(1, 5), Transaction.by(PRINCIPAL)
                .create(delta, "Organization", from(1, 1), values("name", "Delta", "lei", LEI)).build());
        EntityId deltaId = ReferenceTales.entity(created, delta);
        AttributeId lei = attribute("Organization", "lei");
        assertThat(store.ledger().uniqueIds(lei, Value.of(LEI), Ledger.LATEST)).containsExactly(deltaId.value());
        assertThat(mentions(entriesOf(keys), LEI)).isTrue();

        purge(deltaId);

        Map<String, String> after = entriesOf(keys);
        assertThat(mentions(after, LEI)).as("no key mentions the purged LEI").isFalse();
        assertThat(mentions(after, deltaId.value())).isFalse();
        assertThat(mentions(after, "Delta")).isFalse();
        assertThat(after).as("nothing is left of the only entity there was").isEmpty();
        commit(on(2, 1), Transaction.by(PRINCIPAL).create(GAMMA, "Organization", from(2, 1),
                values("name", "Gamma", "lei", LEI)).build());
        assertThat(store.ledger().uniqueIds(lei, Value.of(LEI), Ledger.LATEST)).hasSize(1)
                .doesNotContain(deltaId.value());
    }

    @Test
    void whatRemainsAfterAPurgeReadsAsItDidBeforeAtEveryBasis() {
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, clock);
        EntityId alice = ReferenceTales.entity(told.parties, ALICE);
        List<Id> remaining = new ArrayList<>(told.ids());
        remaining.remove(alice);
        remaining.remove(ReferenceTales.relationship(told.employment, BETA_EMPLOYS_ALICE));
        Basis latest = store.latestBasis();
        Map<Id, List<Timeline>> before = new LinkedHashMap<>();
        for (Id id : remaining) {
            List<Timeline> timelines = new ArrayList<>();
            for (long basis = 0; basis <= latest.value(); basis++) {
                timelines.add(store.ledger().timelineAt(id, new Basis(basis)));
            }
            before.put(id, timelines);
        }

        purge(alice);

        for (Id id : remaining) {
            for (long basis = 0; basis <= latest.value(); basis++) {
                assertThat(store.ledger().timelineAt(id, new Basis(basis))).as("%s at basis %d", id, basis)
                        .isEqualTo(before.get(id).get((int) basis));
            }
        }
        assertThat(store.ledger().entityIds()).doesNotContain(alice);
        assertThat(Set.copyOf(store.ledger().entityIds())).containsAll(remaining.stream()
                .filter(EntityId.class::isInstance).map(EntityId.class::cast).toList());
    }

    @Test
    void aPurgeIsOneBatchThatDeletesRelationshipsBeforeEntities() {
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, clock);
        EntityId alice = ReferenceTales.entity(told.parties, ALICE);
        int batches = keys.batches().size();

        purge(alice);

        assertThat(keys.batches()).hasSize(batches + 1);
        var operations = keys.batches().getLast().operations();
        assertThat(operations).allSatisfy(operation ->
                assertThat(operation).isInstanceOf(Batch.Delete.class));
        List<String> ids = operations.stream().map(operation -> idsOf(HEX.formatHex(operation.key())).getFirst())
                .toList();
        assertThat(ids.indexOf(alice.value())).as("the relationship's keys come before the entity's")
                .isGreaterThan(ids.indexOf(ReferenceTales.relationship(told.employment, BETA_EMPLOYS_ALICE).value()));
    }
}
