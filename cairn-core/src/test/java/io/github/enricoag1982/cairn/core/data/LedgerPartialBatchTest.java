package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A reader that runs while a batch is being applied may see part of it, and must still read what it read before
 * (ADR-0027, design D1 and D4 of {@code refactor-storage-onto-key-value-store}): every key and value says the bases it
 * belongs to, so a view at an older basis ignores the rows, the markers, the current timelines and the index entries that
 * a later transaction adds. The test applies the batch of a later commit to a key-value store operation by operation,
 * and after each one reads every older basis; the reads never change, and a reader at the later basis reads all of it
 * once the batch is applied.
 */
class LedgerPartialBatchTest {

    private final Recorder recorder = new Recorder();
    private final LedgerOracle oracle = new LedgerOracle();
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private RelationshipId owns;
    private Basis last;
    private Basis richest;

    private Preparation commit(Instant recordedAt, Transaction transaction) {
        recorder.clock.moveTo(recordedAt);
        Preparation preparation = recorder.commit(transaction);
        oracle.record(preparation.commit().basis(), preparation.changes());
        return preparation;
    }

    @BeforeEach
    void historyThatTheLastCommitChangesInEveryWayABatchCan() {
        Preparation parties = commit(on(1, 5), Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());
        acme = (EntityId) parties.temporaryIds().get(ACME);
        beta = (EntityId) parties.temporaryIds().get(BETA);
        alice = (EntityId) parties.temporaryIds().get(ALICE);
        owns = (RelationshipId) commit(on(2, 10), Transaction.by("importer")
                .relate(ACME_OWNS_BETA, "Owns", acme, beta, from(2, 1), values("share", 60)).build())
                .temporaryIds().get(ACME_OWNS_BETA);
        commit(on(3, 1), Transaction.by("importer").patch(alice, from(3, 1), values("name", "Alice Smith")).build());
        last = recorder.sequence.latestBasis();
        // The last commit creates and relates, changes two timelines, supersedes rows and adds a unique value.
        commit(on(5, 2), Transaction.by("importer")
                .patch(owns, from(2, 1), values("share", 55))
                .patch(acme, from(4, 1), values("name", "ACME Holdings", "lei", LEI))
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate(GAMMA_OWNS_BETA, "Owns", GAMMA, beta, from(5, 1), values("share", 10))
                .build());
        richest = recorder.sequence.latestBasis();
    }

    private Schema schema() {
        return recorder.registry.current();
    }

    private TypeId type(String name) {
        return schema().entityType(name).orElseThrow().id();
    }

    /** Everything that a reader at a basis below the last commit can ask of a ledger, as text. */
    private List<Object> reads(Ledger ledger, Basis basis) {
        TypeId owned = schema().relationshipType("Owns").orElseThrow().id();
        AttributeId lei = schema().entityType("Organization").orElseThrow().attribute("lei").orElseThrow().id();
        List<Object> reads = new ArrayList<>();
        for (EntityId id : List.of(acme, beta, alice)) {
            reads.add(ledger.entity(id, basis));
            reads.add(ledger.timelineAt(id, basis));
            reads.add(ledger.versions(id, basis));
        }
        reads.add(ledger.relationship(owns, basis));
        reads.add(ledger.timelineAt(owns, basis));
        reads.add(ledger.versions(owns, basis));
        reads.add(ledger.entities(Set.of(type("Organization")), basis));
        reads.add(ledger.entities(Set.of(type("Person"), type("Organization")), basis));
        for (EntityId end : List.of(acme, beta)) {
            for (Ledger.Direction direction : Ledger.Direction.values()) {
                reads.add(ledger.relationships(end, Set.of(owned), direction, basis));
            }
            reads.add(ledger.ends(end, basis));
        }
        reads.add(ledger.uniqueIds(lei, Value.of(LEI), basis));

        StoreView view = new StoreView(on(5, 15), basis, new SchemaIndex(schema()), ledger);
        reads.add(view.entity(acme));
        reads.add(view.entities("Organization"));
        reads.add(view.timeline(acme));
        List<Link> links = view.navigate(acme, "Owns");
        reads.add(links);
        reads.add(view.navigate(beta, "ownedBy"));
        reads.add(view.relationshipTimeline(owns));
        return reads;
    }

    /** A ledger on a fresh store that holds the first commits and only the first operations of the last batch. */
    private Ledger ledgerWithPartOfTheLastBatch(int operations) {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        List<Batch> batches = recorder.keys.batches();
        for (Batch batch : batches.subList(0, batches.size() - 1)) {
            store.apply(batch);
        }
        Batch part = new Batch();
        for (Batch.Operation operation : batches.getLast().operations().subList(0, operations)) {
            switch (operation) {
                case Batch.Put put -> part.put(put.key(), put.value());
                case Batch.Delete delete -> part.delete(delete.key());
            }
        }
        store.apply(part);
        return new Ledger(store);
    }

    @Test
    void aViewAtAnOlderBasisReadsTheSameWhateverPartOfALaterBatchIsThere() {
        int size = recorder.keys.batches().getLast().size();
        assertThat(size).as("a batch with rows, markers, current keys and index keys").isGreaterThan(12);
        assertThat(richest.value()).isGreaterThan(last.value());
        List<List<Object>> before = new ArrayList<>();
        for (long basis = 0; basis <= last.value(); basis++) {
            before.add(reads(ledgerWithPartOfTheLastBatch(0), new Basis(basis)));
        }

        for (int operations = 1; operations <= size; operations++) {
            Ledger ledger = ledgerWithPartOfTheLastBatch(operations);
            for (long basis = 0; basis <= last.value(); basis++) {
                assertThat(reads(ledger, new Basis(basis)))
                        .as("reads at basis %d after %d of %d operations of the later batch", basis, operations, size)
                        .isEqualTo(before.get((int) basis));
            }
        }
    }

    @Test
    void theReadsBeforeALaterBatchAreWhatTheOracleRecorded() {
        Ledger ledger = ledgerWithPartOfTheLastBatch(0);

        for (long basis = 0; basis <= last.value(); basis++) {
            for (EntityId id : List.of(acme, beta, alice)) {
                assertThat(ledger.timelineAt(id, new Basis(basis))).isEqualTo(oracle.at(id, new Basis(basis)));
            }
            assertThat(ledger.timelineAt(owns, new Basis(basis))).isEqualTo(oracle.at(owns, new Basis(basis)));
        }
    }

    @Test
    void aReaderAtTheLaterBasisReadsAllOfTheBatchOnceItIsApplied() {
        int size = recorder.keys.batches().getLast().size();
        Ledger whole = ledgerWithPartOfTheLastBatch(size);

        for (EntityId id : List.of(acme, beta, alice)) {
            assertThat(whole.timelineAt(id, richest)).isEqualTo(oracle.at(id, richest));
        }
        assertThat(whole.timelineAt(owns, richest)).isEqualTo(oracle.at(owns, richest));
        assertThat(whole.entities(Set.of(type("Organization")), richest)).hasSize(3);
        assertThat(whole.entity(acme, richest)).hasValueSatisfying(held ->
                assertThat(held.timeline().segments()).extracting(segment -> segment.values().size())
                        .as("ACME's name and LEI from April").contains(2));
        EntityState state = new StoreView(on(5, 15), richest, new SchemaIndex(schema()), whole).entity(acme)
                .orElseThrow();
        assertThat(state.values()).containsEntry("name", Value.of("ACME Holdings")).containsEntry("lei", Value.of(LEI));
    }
}
