package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a purge does with the stored transactions that named an entity by a unique key (ADR-0026, ADR-0024, design D4a of
 * {@code add-unique-key-references}): the receipt of an idempotent transaction lists the entity that each key resolved
 * to, so a purge of that entity counts the transaction as one that wrote purged data, drops it and its receipt, and keeps
 * its idempotency key as purged. The key's value, which may be personal data, then leaves memory and, through the files
 * that a purge rewrites, the disk; recovery rebuilds what a purge needs from the log and the snapshot files.
 */
class KeyedPurgeTest {

    private static final String IDEMPOTENCY_KEY = "rename-acme";
    private static final UniqueKey ACME_LEI = new UniqueKey("Organization", "lei", Value.of(LEI));

    @TempDir
    Path directory;

    private DurableFixture durable;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    /** What a test keeps of a store in which ACME has an LEI and an idempotent transaction named ACME by it. */
    private record Written(EntityId acme, EntityId alice, Transaction keyed, Receipt receipt) {
    }

    private static Transaction renamesAcmeByItsLei() {
        return Transaction.by("importer").idempotencyKey(IDEMPOTENCY_KEY)
                .patch(ACME_LEI, from(2, 1), values("name", "ACME Holdings")).build();
    }

    /** RD-1, RD-9 and the transaction that renames ACME by its LEI, with an idempotency key. */
    private static Written write(Store store, TestClock clock) {
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        clock.moveTo(on(2, 1));
        Transaction keyed = renamesAcmeByItsLei();
        CommitResult result = store.commit(keyed);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        assertThat(receipt.keys()).as("the receipt names ACME for the key")
                .containsExactly(new KeyResolution(0, ACME_LEI, acme));
        return new Written(acme, entity(parties, ALICE), keyed, receipt);
    }

    private static Purge purgeOf(EntityId entity) {
        return Purge.by("dpo").reason("DSR-2026-0042").entity(entity).build();
    }

    private static void purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
    }

    private static void assertRetryRejectedBecauseOfThePurge(Store store, Transaction keyed) {
        CommitResult retry = store.commit(keyed);

        assertThat(retry).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) retry).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
            assertThat(problem.path()).isEqualTo("@transaction");
        });
    }

    private static void assertRetryGetsTheFirstReceipt(Store store, Written written) {
        assertThat(store.commit(written.keyed())).isEqualTo(new CommitResult.Committed(written.receipt()));
    }

    // ------------------------------------------------------------------ the in-memory store

    private InMemoryStore inMemory(TestClock clock) {
        InMemoryStore store = new InMemoryStore(clock, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        return store;
    }

    @Test
    void thePurgeOfTheEntityThatAKeyNamedDropsTheStoredTransactionAndKeepsItsIdempotencyKeyAsPurged() {
        TestClock clock = new TestClock();
        InMemoryStore store = inMemory(clock);
        Written written = write(store, clock);
        assertThat(store.keys()).as("the store holds the transaction with the LEI").containsOnlyKeys(IDEMPOTENCY_KEY);
        assertThat(store.keys().get(IDEMPOTENCY_KEY).transaction()).isEqualTo(written.keyed());
        assertThat(store.keys().get(IDEMPOTENCY_KEY).receipt()).isEqualTo(written.receipt());

        purged(store.purge(purgeOf(written.acme())));

        assertThat(store.keys()).as("the keyed map no longer holds the transaction").isEmpty();
        assertThat(store.purgedKeys()).containsExactly(IDEMPOTENCY_KEY);
        assertRetryRejectedBecauseOfThePurge(store, written.keyed());
        assertThat(store.keys()).as("and the rejected retry stores nothing").isEmpty();
    }

    @Test
    void thePurgeOfAnotherEntityLeavesTheTransactionThatNamedAKeyAndItsReceipt() {
        TestClock clock = new TestClock();
        InMemoryStore store = inMemory(clock);
        Written written = write(store, clock);

        purged(store.purge(purgeOf(written.alice())));

        assertThat(store.keys()).containsOnlyKeys(IDEMPOTENCY_KEY);
        assertThat(store.purgedKeys()).isEmpty();
        assertRetryGetsTheFirstReceipt(store, written);
    }

    @Test
    void aTransactionWithoutAnIdempotencyKeyLeavesNothingToDrop() {
        TestClock clock = new TestClock();
        InMemoryStore store = inMemory(clock);
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        clock.moveTo(on(2, 1));
        assertThat(store.commit(Transaction.by("importer")
                .patch(ACME_LEI, from(2, 1), values("name", "ACME Holdings")).build()))
                .isInstanceOf(CommitResult.Committed.class);

        purged(store.purge(purgeOf(acme)));

        assertThat(store.keys()).isEmpty();
        assertThat(store.purgedKeys()).isEmpty();
    }

    @Test
    void aKeyThatResolvedToDifferentEntitiesInTwoWritesIsDroppedWhicheverOfThemIsPurged() {
        TestClock clock = new TestClock();
        InMemoryStore store = inMemory(clock);
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        // The LEI is ACME's until 1 March, and Gamma's from then on.
        store.commit(Transaction.by("importer").patch(acme, EffectivePeriod
                .between(on(1, 1), on(3, 1)), values("lei", LEI)).build());
        EntityId gamma = entity(ReferenceStories.replay(store, clock, ReferenceStories.rd9Gamma()),
                ReferenceStories.GAMMA);
        clock.moveTo(on(5, 1));
        Transaction both = Transaction.by("importer").idempotencyKey("both")
                .patch(ACME_LEI, EffectivePeriod.between(on(2, 1), on(2, 2)),
                        values("name", "ACME in February"))
                .patch(ACME_LEI, EffectivePeriod.between(on(4, 1), on(4, 2)),
                        values("name", "Gamma in April")).build();
        Receipt receipt = ((CommitResult.Committed) store.commit(both)).receipt();
        assertThat(receipt.keys()).extracting(KeyResolution::entity).containsExactly(acme, gamma);

        purged(store.purge(purgeOf(gamma)));

        assertThat(store.keys()).isEmpty();
        assertThat(store.purgedKeys()).containsExactly("both");
    }

    // ------------------------------------------------------------------ durable stores

    private void assertTheLeiIsOnDisk() {
        assertThat(StoreFiles.filesContaining(directory, LEI)).as("the files hold the LEI before the purge")
                .isNotEmpty();
    }

    private void assertTheLeiIsNotOnDisk(String when) {
        assertThat(StoreFiles.filesContaining(directory, LEI)).as("the files " + when).isEmpty();
    }

    @Test
    void aStoreThatWasClosedRebuildsWhatThePurgeNeedsFromTheSnapshotFile() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first, durable.clock);
        first.close();
        DirectoryStore reopened = durable.open();
        assertRetryGetsTheFirstReceipt(reopened, written);
        assertTheLeiIsOnDisk();

        purged(reopened.purge(purgeOf(written.acme())));

        assertTheLeiIsNotOnDisk("after the purge");
        assertRetryRejectedBecauseOfThePurge(reopened, written.keyed());
        reopened.close();
        assertTheLeiIsNotOnDisk("after closing");
        assertRetryRejectedBecauseOfThePurge(durable.open(), written.keyed());
        assertTheLeiIsNotOnDisk("after opening again");
    }

    @Test
    void aStoreThatWasRecoveredFromTheLogAloneRebuildsWhatThePurgeNeedsFromTheReceiptsOfTheLog() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first, durable.clock);
        DurableFixture.crash(first);
        assertThat(StoreFiles.snapshots(directory)).as("no snapshot file: the log is all there is").isEmpty();
        DirectoryStore recovered = durable.open();
        assertRetryGetsTheFirstReceipt(recovered, written);

        purged(recovered.purge(purgeOf(written.acme())));

        assertTheLeiIsNotOnDisk("after the purge");
        assertRetryRejectedBecauseOfThePurge(recovered, written.keyed());
    }

    @Test
    void aPurgeThatReachedTheLogIsAppliedByRecoveryWithTheTransactionsThatNamedTheEntityByAKey() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first, durable.clock);
        first.writeSnapshot();
        first.crashAfterTheNextPurgeIsLogged();
        assertThatThrownBy(() -> first.purge(purgeOf(written.acme())))
                .isInstanceOf(IllegalStateException.class);
        assertTheLeiIsOnDisk();

        DirectoryStore recovered = durable.open();

        assertTheLeiIsNotOnDisk("after the purge was completed by opening the store");
        assertRetryRejectedBecauseOfThePurge(recovered, written.keyed());
        assertThat(recovered.wasPurged(written.acme())).isTrue();
    }

    @Test
    void aReopenedStoreKeepsTheTransactionThatNamedAKeyWhenAnotherEntityIsPurged() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first, durable.clock);
        first.close();
        DirectoryStore reopened = durable.open();

        purged(reopened.purge(purgeOf(written.alice())));

        assertRetryGetsTheFirstReceipt(reopened, written);
        reopened.close();
        assertRetryGetsTheFirstReceipt(durable.open(), written);
    }

    @Test
    void theKeyResolutionsOfTheReceiptSurviveEveryWayOfOpeningAStore() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first, durable.clock);
        DurableFixture.crash(first);

        DirectoryStore fromTheLog = durable.open();
        List<KeyResolution> fromLog = ((CommitResult.Committed) fromTheLog.commit(written.keyed())).receipt().keys();
        fromTheLog.close();
        List<KeyResolution> fromSnapshot = ((CommitResult.Committed) durable.open().commit(written.keyed())).receipt()
                .keys();

        assertThat(fromLog).isEqualTo(written.receipt().keys());
        assertThat(fromSnapshot).isEqualTo(written.receipt().keys());
    }
}
