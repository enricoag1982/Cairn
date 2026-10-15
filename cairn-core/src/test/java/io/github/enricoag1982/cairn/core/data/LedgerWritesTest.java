package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.RecordingKeyValueStore;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec.Kind;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The keys that a commit writes (ADR-0027, design D2 and D3 of {@code refactor-storage-onto-key-value-store}), read
 * from the batches that a store applies to its key-value store: the current key of what the commit changes, a row for
 * each segment that is new, a marker for each segment that is gone, the index keys of what is new, and nothing at all
 * for a transaction that changes nothing. The stories are those of the reference domain.
 */
class LedgerWritesTest {

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

    private Receipt replay(ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    private Receipt commit(Instant recordedAt, Transaction transaction) {
        clock.moveTo(recordedAt);
        CommitResult result = store.commit(transaction);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    private Batch last() {
        return keys.batches().getLast();
    }

    private TypeId entityType(String name) {
        return schema.entityType(name).orElseThrow().id();
    }

    private TypeId relationshipType(String name) {
        return schema.relationshipType(name).orElseThrow().id();
    }

    private AttributeId attribute(String type, String name) {
        return schema.entityType(type).orElseThrow().attribute(name).orElseThrow().id();
    }

    // ------------------------------------------------------------------ reading a batch

    private static String hex(byte[] key) {
        return HEX.formatHex(key);
    }

    /** The keys that a batch puts, as text, in the order of the batch. */
    private static List<String> puts(Batch batch) {
        return batch.operations().stream().filter(Batch.Put.class::isInstance).map(operation -> hex(operation.key()))
                .toList();
    }

    /** The value that a batch puts for a key. */
    private static byte[] valueOf(Batch batch, byte[] key) {
        return batch.operations().stream().filter(Batch.Put.class::isInstance).map(Batch.Put.class::cast)
                .filter(put -> Arrays.equals(put.key(), key)).map(Batch.Put::value).findFirst().orElseThrow();
    }

    private static Map<Kind, Long> counts(Batch batch) {
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        for (Batch.Operation operation : batch.operations()) {
            counts.merge(LedgerCodec.kindOf(operation.key()), 1L, Long::sum);
        }
        return counts;
    }

    private static LedgerCodec.CurrentValue currentOf(Batch batch, Id id) {
        return LedgerCodec.decodeCurrent(valueOf(batch, LedgerCodec.currentKey(id.value())));
    }

    private static List<Basis> insertedAt(LedgerCodec.CurrentValue current) {
        return current.segments().stream().map(LedgerCodec.Placed::insertedAt).toList();
    }

    // ------------------------------------------------------------------ the tests

    @Test
    void creatingEntitiesWritesTheirCurrentKeysRowsAndTypeKeysInOneBatch() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Basis basis = parties.basis();

        assertThat(keys.batches()).as("one batch for the whole commit").hasSize(1);
        assertThat(counts(last())).containsEntry(Kind.CURRENT, 3L).containsEntry(Kind.ROW, 3L)
                .containsEntry(Kind.TYPE, 3L).containsEntry(Kind.CHANGE, 3L).hasSize(4);
        assertThat(puts(last())).containsExactlyInAnyOrder(
                hex(LedgerCodec.currentKey(acme.value())), hex(LedgerCodec.currentKey(beta.value())),
                hex(LedgerCodec.currentKey(alice.value())),
                hex(LedgerCodec.changeKey(basis, acme.value())), hex(LedgerCodec.changeKey(basis, beta.value())),
                hex(LedgerCodec.changeKey(basis, alice.value())),
                hex(LedgerCodec.rowKey(acme.value(), basis, on(1, 1))),
                hex(LedgerCodec.rowKey(beta.value(), basis, on(1, 1))),
                hex(LedgerCodec.rowKey(alice.value(), basis, on(1, 1))),
                hex(LedgerCodec.typeKey(entityType("Organization"), acme)),
                hex(LedgerCodec.typeKey(entityType("Organization"), beta)),
                hex(LedgerCodec.typeKey(entityType("Person"), alice)));
        assertThat(LedgerCodec.decodeBasis(valueOf(last(), LedgerCodec.typeKey(entityType("Person"), alice))))
                .as("an index key holds the basis that added it").isEqualTo(basis);
        LedgerCodec.CurrentValue current = currentOf(last(), alice);
        assertThat(current.relationship()).isFalse();
        assertThat(current.type()).isEqualTo(entityType("Person"));
        assertThat(current.lastBasis()).isEqualTo(basis);
        assertThat(current.source()).isEmpty();
        assertThat(insertedAt(current)).containsExactly(basis);
        assertThat(current.segments().getFirst().segment().values())
                .containsEntry(attribute("Person", "name"), Value.of("Alice"));
        assertThat(LedgerCodec.decodeRow(on(1, 1), valueOf(last(), LedgerCodec.rowKey(alice.value(), basis,
                on(1, 1)))).end()).as("a row holds the segment without its start").isEqualTo(Instant.MAX);
    }

    @Test
    void relatingWritesTheRelationshipAndItsKeyAtBothOfItsEndsButNoTypeKey() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);

        Receipt ownership = replay(ReferenceStories.rd2Ownership(acme, beta));
        RelationshipId owns = relationship(ownership, ACME_OWNS_BETA);
        Basis basis = ownership.basis();

        assertThat(keys.batches()).hasSize(2);
        assertThat(puts(last())).containsExactlyInAnyOrder(
                hex(LedgerCodec.currentKey(owns.value())),
                hex(LedgerCodec.changeKey(basis, owns.value())),
                hex(LedgerCodec.rowKey(owns.value(), basis, on(2, 1))),
                hex(LedgerCodec.endKey(acme.value(), relationshipType("Owns"), true, owns.value())),
                hex(LedgerCodec.endKey(beta.value(), relationshipType("Owns"), false, owns.value())));
        LedgerCodec.CurrentValue current = currentOf(last(), owns);
        assertThat(current.relationship()).isTrue();
        assertThat(current.source()).contains(acme);
        assertThat(current.target()).contains(beta);
        assertThat(LedgerCodec.decodeBasis(valueOf(last(),
                LedgerCodec.endKey(beta.value(), relationshipType("Owns"), false, owns.value()))))
                .isEqualTo(basis);
    }

    @Test
    void aCorrectionWritesANewRowAndAMarkerThatNamesTheRowItSupersededAndTheBasisThatDidSo() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        Receipt ownership = replay(ReferenceStories.rd2Ownership(entity(parties, ACME), entity(parties, BETA)));
        RelationshipId owns = relationship(ownership, ACME_OWNS_BETA);

        Receipt correction = replay(ReferenceStories.rd2ShareCorrection(owns));

        Basis then = ownership.basis();
        Basis now = correction.basis();
        assertThat(counts(last())).containsEntry(Kind.CURRENT, 1L).containsEntry(Kind.ROW, 1L)
                .containsEntry(Kind.SUPERSEDED, 1L).containsEntry(Kind.CHANGE, 1L).hasSize(4);
        assertThat(puts(last())).containsExactlyInAnyOrder(
                hex(LedgerCodec.currentKey(owns.value())),
                hex(LedgerCodec.changeKey(now, owns.value())),
                hex(LedgerCodec.rowKey(owns.value(), now, on(2, 1))),
                hex(LedgerCodec.supersededKey(owns.value(), then, on(2, 1))));
        assertThat(LedgerCodec.decodeBasis(valueOf(last(), LedgerCodec.supersededKey(owns.value(), then, on(2, 1)))))
                .as("a marker holds the basis that superseded the row").isEqualTo(now);
        assertThat(insertedAt(currentOf(last(), owns))).containsExactly(now);
        assertThat(keys.get(LedgerCodec.rowKey(owns.value(), then, on(2, 1)))).as("the superseded row stays").isPresent();
    }

    @Test
    void aChangeWritesRowsOnlyForTheSegmentsThatAreNewAndLeavesTheOnesItDoesNotTouch() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        Receipt accounts = replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        EntityId a1 = entity(accounts, A1);
        Receipt first = replay(ReferenceStories.rd5BalancePatch(a1));

        assertThat(counts(last())).as("the first part is cut at 15 March, so it is a new row too")
                .containsEntry(Kind.CURRENT, 1L).containsEntry(Kind.ROW, 2L).containsEntry(Kind.SUPERSEDED, 1L);
        assertThat(insertedAt(currentOf(last(), a1))).containsExactly(first.basis(), first.basis());

        Receipt second = commit(on(6, 20), Transaction.by(PRINCIPAL)
                .patch(a1, from(6, 1), values("balance", money("2000"))).build());

        assertThat(puts(last())).as("the segment before 15 March is as it was: no row, no marker")
                .containsExactlyInAnyOrder(
                        hex(LedgerCodec.currentKey(a1.value())),
                        hex(LedgerCodec.changeKey(second.basis(), a1.value())),
                        hex(LedgerCodec.rowKey(a1.value(), second.basis(), on(3, 15))),
                        hex(LedgerCodec.rowKey(a1.value(), second.basis(), on(6, 1))),
                        hex(LedgerCodec.supersededKey(a1.value(), first.basis(), on(3, 15))));
        LedgerCodec.CurrentValue current = currentOf(last(), a1);
        assertThat(current.segments()).extracting(placed -> placed.segment().from())
                .containsExactly(on(1, 1), on(3, 15), on(6, 1));
        assertThat(insertedAt(current)).as("each segment names the transaction that inserted it")
                .containsExactly(first.basis(), second.basis(), second.basis());
    }

    @Test
    void entitiesThatAreCreatedAndChangedInOneTransactionHaveOneRowPerSegment() {
        TemporaryId bob = new TemporaryId("bob");

        Receipt receipt = commit(on(1, 5), Transaction.by(PRINCIPAL)
                .create(bob, "Person", from(1, 1), values("name", "Bob"))
                .patch(bob, from(3, 1), values("name", "Robert")).build());

        EntityId id = entity(receipt, bob);
        assertThat(counts(last())).containsEntry(Kind.CURRENT, 1L).containsEntry(Kind.ROW, 2L)
                .containsEntry(Kind.TYPE, 1L).containsEntry(Kind.CHANGE, 1L).hasSize(4);
        assertThat(insertedAt(currentOf(last(), id))).containsExactly(receipt.basis(), receipt.basis());
    }

    @Test
    void aTransactionThatChangesNothingWritesNothing() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        int batches = keys.batches().size();
        Basis before = store.latestBasis();

        Receipt receipt = commit(on(2, 1), Transaction.by(PRINCIPAL)
                .patch(alice, from(1, 1), values("name", "Alice")).build());

        assertThat(receipt.basis()).as("the transaction is recorded").isGreaterThan(before);
        assertThat(keys.batches()).as("but it writes no key").hasSize(batches);
    }

    @Test
    void aTransactionThatIsRejectedOrOnlyTriedWritesNothing() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        int batches = keys.batches().size();
        clock.moveTo(on(2, 1));

        Transaction invalid = Transaction.by(PRINCIPAL).patch(alice, from(2, 1), values("name", 5)).build();
        Transaction valid = Transaction.by(PRINCIPAL).patch(alice, from(2, 1), values("name", "Alicia")).build();

        assertThat(store.commit(invalid)).isInstanceOf(CommitResult.Rejected.class);
        assertThat(store.dryRun(valid)).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(keys.batches()).hasSize(batches);
    }

    @Test
    void aValueOfAUniqueAttributeIsIndexedWithTheBasisThatAddedItAndNeverAgainForTheSameEntity() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        Receipt accounts = replay(ReferenceStories.rd5Accounts(entity(parties, BETA), acme));
        EntityId a1 = entity(accounts, A1);
        AttributeId number = attribute("Account", "number");
        byte[] keyOfA1 = LedgerCodec.uniqueKey(number, Value.of("A-1"), a1.value());
        assertThat(counts(last())).as("one key for each account number").containsEntry(Kind.UNIQUE, 2L);
        assertThat(LedgerCodec.decodeBasis(valueOf(last(), keyOfA1))).isEqualTo(accounts.basis());

        replay(ReferenceStories.rd5BalancePatch(a1));
        assertThat(counts(last())).as("a change of another attribute").doesNotContainKey(Kind.UNIQUE);

        Receipt renamed = commit(on(7, 1), Transaction.by(PRINCIPAL)
                .patch(a1, from(7, 1), values("number", "A-9")).build());
        byte[] keyOfA9 = LedgerCodec.uniqueKey(number, Value.of("A-9"), a1.value());
        assertThat(puts(last())).as("the new value is indexed, the old one stays").contains(hex(keyOfA9))
                .doesNotContain(hex(keyOfA1));
        assertThat(LedgerCodec.decodeBasis(valueOf(last(), keyOfA9))).isEqualTo(renamed.basis());

        commit(on(8, 1), Transaction.by(PRINCIPAL).patch(a1, from(8, 1), values("number", "A-1")).build());
        assertThat(counts(last())).as("the old value comes back: it is indexed already")
                .doesNotContainKey(Kind.UNIQUE);
        assertThat(LedgerCodec.decodeBasis(keys.get(keyOfA1).orElseThrow()))
                .as("and keeps the basis that added it").isEqualTo(accounts.basis());
    }

    @Test
    void aUniqueValueThatAnEntityGetsLaterIsIndexedWhenItGetsIt() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);

        Receipt receipt = replay(ReferenceStories.rd9AcmeLei(acme));

        assertThat(puts(last())).contains(hex(LedgerCodec.uniqueKey(attribute("Organization", "lei"), Value.of(LEI),
                acme.value())));
        assertThat(counts(last())).containsEntry(Kind.UNIQUE, 1L).doesNotContainKey(Kind.TYPE);
        assertThat(LedgerCodec.decodeBasis(valueOf(last(), LedgerCodec.uniqueKey(attribute("Organization", "lei"),
                Value.of(LEI), acme.value())))).isEqualTo(receipt.basis());
    }

    @Test
    void endingEntitiesWritesRowsAndMarkersForWhatIsCutOffAndNoIndexKeys() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId beta = entity(parties, BETA);
        EntityId acme = entity(parties, ACME);
        Receipt accounts = replay(ReferenceStories.rd5Accounts(beta, acme));
        EntityId a1 = entity(accounts, A1);

        Receipt ending = commit(on(7, 1), Transaction.by(PRINCIPAL)
                .endDate(beta, on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values())
                .endDate(relationship(accounts, HELD_BY_A1), on(6, 1)).build());

        Map<Kind, Long> counts = counts(last());
        assertThat(counts).containsOnlyKeys(Kind.CURRENT, Kind.ROW, Kind.SUPERSEDED, Kind.END, Kind.CHANGE);
        assertThat(counts.get(Kind.SUPERSEDED)).as("one cut segment each for Beta and the old HeldBy").isEqualTo(2L);
        assertThat(counts.get(Kind.END)).as("the new HeldBy at its two ends").isEqualTo(2L);
        assertThat(ending.ended()).isEmpty();
        assertThat(puts(last())).contains(
                hex(LedgerCodec.rowKey(beta.value(), ending.basis(), on(1, 1))),
                hex(LedgerCodec.supersededKey(beta.value(), parties.basis(), on(1, 1))));
        assertThat(LedgerCodec.decodeRow(on(1, 1), valueOf(last(), LedgerCodec.rowKey(beta.value(), ending.basis(),
                on(1, 1)))).end()).isEqualTo(on(6, 1));
    }

    // ------------------------------------------------------------------ the index of changes

    private LedgerCodec.ChangeValue changeOf(Batch batch, Basis basis, Id id) {
        return LedgerCodec.decodeChange(valueOf(batch, LedgerCodec.changeKey(basis, id.value())));
    }

    @Test
    void aChangeKeyHoldsWhetherItIsARelationshipItsTypeWhetherTheCommitCreatedItAndThePeriodsItChangedIn() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt ownership = replay(ReferenceStories.rd2Ownership(acme, beta));
        RelationshipId owns = relationship(ownership, ACME_OWNS_BETA);
        assertThat(changeOf(last(), ownership.basis(), owns)).isEqualTo(new LedgerCodec.ChangeValue(true,
                relationshipType("Owns"), true, List.of(EffectivePeriod.from(on(2, 1)))));

        Receipt correction = replay(ReferenceStories.rd2ShareCorrection(owns));

        assertThat(changeOf(last(), correction.basis(), owns)).as("a correction is not a creation")
                .isEqualTo(new LedgerCodec.ChangeValue(true, relationshipType("Owns"), false,
                        List.of(EffectivePeriod.from(on(2, 1)))));
        assertThat(keys.get(LedgerCodec.changeKey(ownership.basis(), owns.value()))).as("the earlier key stays")
                .isPresent();
        assertThat(keys.get(LedgerCodec.changeKey(correction.basis(), acme.value()))).as("what was not changed has none")
                .isEmpty();
    }

    @Test
    void aWriteThatCutsATimelineNamesOnlyThePeriodThatChanged() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        Receipt accounts = replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        EntityId a1 = entity(accounts, A1);
        replay(ReferenceStories.rd5BalancePatch(a1));

        Receipt middle = commit(on(6, 20), Transaction.by(PRINCIPAL)
                .patch(a1, EffectivePeriod.between(on(5, 1), on(6, 1)), values("balance", money("900"))).build());

        assertThat(changeOf(last(), middle.basis(), a1).periods())
                .containsExactly(EffectivePeriod.between(on(5, 1), on(6, 1)));
    }

    @Test
    void aTransactionThatRecordsTheSameValuesAgainWritesNoChangeKeyBecauseItRecordsNothing() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        int batches = keys.batches().size();

        commit(on(2, 1), Transaction.by(PRINCIPAL).patch(alice, from(1, 1), values("name", "Alice")).build());

        assertThat(keys.batches()).hasSize(batches);
    }

    @Test
    void whatTheKeysOfACommitHoldReadsBackThroughTheLedger() {
        Receipt parties = replay(ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);

        Optional<Held> held = store.ledger().entity(acme);

        assertThat(held).hasValueSatisfying(found -> {
            assertThat(found.type()).isEqualTo(entityType("Organization"));
            assertThat(found.basis()).isEqualTo(parties.basis());
            assertThat(found.timeline().segments()).singleElement().satisfies(segment -> {
                assertThat(segment.from()).isEqualTo(on(1, 1));
                assertThat(segment.end()).isEqualTo(Segment.OPEN);
                assertThat(segment.recordedAt()).isEqualTo(parties.transactionTime());
                assertThat(segment.recordedBasis()).isEqualTo(parties.basis());
            });
        });
        assertThat(keys.entries()).as("nothing but keys of a ledger").allSatisfy(entry ->
                assertThat(LedgerCodec.kindOf(entry.key())).isNotNull());
    }
}
