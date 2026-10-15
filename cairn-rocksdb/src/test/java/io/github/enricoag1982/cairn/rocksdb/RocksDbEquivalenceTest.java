package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.CUSTOMER_ROLE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A RocksDB store and an in-memory store, with the same clock and the same deterministic ID generator, commit every story
 * of the reference domain, rejected ones included, and purge, and are compared before the RocksDB store is closed and
 * opened again and after (the equivalence tests of durable stores, for RocksDB stores).
 */
class RocksDbEquivalenceTest {

    @TempDir
    Path directory;

    private RocksDbFixture rocks;
    private final TestClock memoryClock = new TestClock();
    private Store memory;

    @BeforeEach
    void setUp() {
        rocks = new RocksDbFixture(directory);
        memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build());
        RocksDbFixture.registerReferenceModel(memory);
    }

    @AfterEach
    void tearDown() {
        rocks.closeAll();
    }

    /** A transaction with an idempotency key, which both stores commit after the stories. */
    private static Transaction keyed(Receipt parties) {
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        return Transaction.by(ReferenceStories.PRINCIPAL).idempotencyKey("alice-birth-date")
                .reason("The birth date was missing").onBehalfOf("hr")
                .patch(alice, from(1, 1), values("birthDate", LocalDate.of(1990, 5, 17))).build();
    }

    private static StoreEquivalence.Observation observe(Store store, ReferenceTales.Told told,
            List<Transaction> keyed) {
        return StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, keyed);
    }

    @Test
    void aRocksDbStoreIsEquivalentToAnInMemoryStoreBeforeAndAfterReopeningWithTheSameReceiptsAndProblems() {
        DurableStore store = rocks.openWithReferenceModel();

        ReferenceTales.Told inMemory = ReferenceTales.everyStory(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.everyStory(store, rocks.clock);
        Transaction keyed = keyed(inMemory.parties);
        List<Transaction> keys = List.of(keyed);
        RocksDbFixture.commit(memory, keyed);
        RocksDbFixture.commit(store, keyed);

        assertThat(onDisk.receipts).as("the receipts of the accepted stories").isEqualTo(inMemory.receipts);
        assertThat(onDisk.rejected).as("the problems of the rejected stories").isEqualTo(inMemory.rejected);
        assertThat(onDisk.rejected).as("four stories are rejected").hasSize(4);
        StoreEquivalence.Observation expected = observe(memory, inMemory, keys);
        StoreEquivalence.assertEquivalent(expected, observe(store, onDisk, keys));
        assertThat(expected.views()).as("what is compared").hasSizeGreaterThan(1_000);

        store.close();
        DurableStore reopened = rocks.open();
        StoreEquivalence.assertEquivalent(expected, observe(reopened, onDisk, keys));

        // Both stores go on: the reopened store gets the same bases, transaction times and IDs as the other.
        Transaction more = Transaction.by(ReferenceStories.PRINCIPAL)
                .create("Person", from(1, 1), values("name", "Bob")).build();
        Receipt inMemoryMore = RocksDbFixture.commit(memory, more);
        Receipt reopenedMore = RocksDbFixture.commit(reopened, more);
        assertThat(reopenedMore).isEqualTo(inMemoryMore);
        inMemory.receipts.add(inMemoryMore);
        onDisk.receipts.add(reopenedMore);
        StoreEquivalence.assertEquivalent(observe(memory, inMemory, keys), observe(reopened, onDisk, keys));
    }

    private static Purge alicesPurge(ReferenceTales.Told told) {
        return ReferenceStories.rd11PurgeAlice(entity(told.parties, ALICE));
    }

    /** Beta, with the account and the customer role that need it: nothing that remains is left without a relationship. */
    private static Purge betaWithWhatNeedsIt(ReferenceTales.Told told) {
        return Purge.by("dpo").onBehalfOf("DSR-2026-0044").reason("Closure")
                .entity(entity(told.parties, BETA)).entity(entity(told.accounts, A1))
                .entity(entity(told.customer, CUSTOMER_ROLE)).build();
    }

    @Test
    void purgesKeepARocksDbStoreEquivalentToAnInMemoryStoreBeforeAndAfterReopening() {
        DurableStore store = rocks.openWithReferenceModel();
        ReferenceTales.Told inMemory = ReferenceTales.everyStory(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.everyStory(store, rocks.clock);
        Transaction keyed = keyed(inMemory.parties);
        List<Transaction> keys = List.of(keyed);
        RocksDbFixture.commit(memory, keyed);
        RocksDbFixture.commit(store, keyed);
        Purge betaAlone = Purge.by("dpo").reason("Too early").entity(entity(inMemory.parties, BETA)).build();

        PurgeResult refused = memory.purge(betaAlone);
        assertThat(refused).isInstanceOf(PurgeResult.Rejected.class);
        assertThat(store.purge(betaAlone)).as("the same problems").isEqualTo(refused);
        PurgeResult alice = memory.purge(alicesPurge(inMemory));
        assertThat(alice).isInstanceOf(PurgeResult.Purged.class);
        assertThat(store.purge(alicesPurge(onDisk))).as("the same record").isEqualTo(alice);

        StoreEquivalence.Observation afterAlice = observe(memory, inMemory, keys);
        assertThat(afterAlice.purges()).hasSize(1);
        assertThat(afterAlice.keyed()).as("the key of the purged transaction is refused").singleElement()
                .isInstanceOf(CommitResult.Rejected.class);
        StoreEquivalence.assertEquivalent(afterAlice, observe(store, onDisk, keys));
        store.close();
        DurableStore reopened = rocks.open();
        StoreEquivalence.assertEquivalent(afterAlice, observe(reopened, onDisk, keys));

        // Both stores purge again, a purge of several entities and what they need, and then commit.
        PurgeResult beta = memory.purge(betaWithWhatNeedsIt(inMemory));
        assertThat(beta).isInstanceOf(PurgeResult.Purged.class);
        assertThat(reopened.purge(betaWithWhatNeedsIt(onDisk))).isEqualTo(beta);
        Transaction more = Transaction.by(ReferenceStories.PRINCIPAL)
                .create("Person", from(1, 1), values("name", "Bob")).build();
        Receipt inMemoryMore = RocksDbFixture.commit(memory, more);
        Receipt reopenedMore = RocksDbFixture.commit(reopened, more);
        assertThat(reopenedMore).as("the same IDs, whatever was purged").isEqualTo(inMemoryMore);
        inMemory.receipts.add(inMemoryMore);
        onDisk.receipts.add(reopenedMore);
        StoreEquivalence.Observation afterBeta = observe(memory, inMemory, keys);
        assertThat(afterBeta.purges()).hasSize(2);
        StoreEquivalence.assertEquivalent(afterBeta, observe(reopened, onDisk, keys));

        reopened.close();
        DurableStore again = rocks.open();
        StoreEquivalence.assertEquivalent(afterBeta, observe(again, onDisk, keys));
        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
        assertThat(StoreFiles.filesContaining(directory, "Analyst")).as("her title, in the Employs relationship")
                .isEmpty();
    }

    @Test
    void theEquivalenceHelperSeesADifference() {
        DurableStore store = rocks.openWithReferenceModel();
        ReferenceTales.Told inMemory = ReferenceTales.rd1ToRd5(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.rd1ToRd5(store, rocks.clock);
        StoreEquivalence.Observation same = observe(memory, inMemory, List.of());
        StoreEquivalence.assertEquivalent(same, observe(store, onDisk, List.of()));

        RocksDbFixture.commit(store, Transaction.by(ReferenceStories.PRINCIPAL)
                .patch((EntityId) onDisk.parties.temporaryIds().get(ReferenceStories.ACME),
                        from(1, 1), values("name", "ACME Ltd")).build());

        assertThatThrownBy(() -> StoreEquivalence.assertEquivalent(same, observe(store, onDisk, List.of())))
                .isInstanceOf(AssertionError.class);
    }
}
