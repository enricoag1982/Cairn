package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.ComparableChanges;
import io.github.enricoag1982.cairn.core.RecordingSubscriber;
import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeChange;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionChange;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index of the change feed in durable stores (ADR-0029, design D6 of {@code add-change-feed}): a store kept in a
 * directory keeps it in memory and recovery writes it again, from a snapshot file, from the log, or from both, and after
 * a purge, which writes a snapshot file of its own; a store kept in RocksDB keeps it with its other keys. Each test reads
 * the changes before a store is closed or stops, and after it is opened again, and finds them the same.
 */
class DurableChangesTest {

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

    /** The changes of a store in a form that two stores, or one store before and after it is opened again, share. */
    private static List<Object> changesOf(Store store) {
        return ComparableChanges.of(store.changes(Basis.NONE, Integer.MAX_VALUE));
    }

    private static Purge alicesPurge(ReferenceTales.Told told) {
        return ReferenceStories.rd11PurgeAlice(entity(told.parties, ALICE));
    }

    // ------------------------------------------------------------------ directory stores

    @Test
    void recoveryFromASnapshotFileWritesTheChangesAgain() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        List<Object> before = changesOf(first);
        first.close();

        DirectoryStore reopened = durable.open();

        assertThat(changesOf(reopened)).hasSize(9).isEqualTo(before);
        assertThat(told.receipts).hasSize(8);
    }

    @Test
    void recoveryFromTheLogAloneWritesTheChangesAgain() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(first, durable.clock);
        List<Object> before = changesOf(first);
        DurableFixture.crash(first);

        DirectoryStore recovered = durable.open();

        assertThat(changesOf(recovered)).hasSize(9).isEqualTo(before);
    }

    @Test
    void recoveryFromASnapshotFileAndTheLogAfterItWritesTheChangesAgain() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        first.writeSnapshot();
        durable.replay(first, ReferenceStories.rd7Rename(entity(told.parties, ReferenceStories.ACME)));
        durable.replay(first, ReferenceStories.rd9AcmeLei(entity(told.parties, ReferenceStories.ACME)));
        List<Object> before = changesOf(first);
        DurableFixture.crash(first);

        DirectoryStore recovered = durable.open();

        assertThat(changesOf(recovered)).hasSize(11).isEqualTo(before);
    }

    @Test
    void recoveryAfterAPurgeWritesTheChangesAgainWithoutWhatItRemoved() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        assertThat(first.purge(alicesPurge(told))).isInstanceOf(PurgeResult.Purged.class);
        durable.replay(first, ReferenceStories.rd7Rename(entity(told.parties, ReferenceStories.ACME)));
        List<Object> before = changesOf(first);
        assertThat(before).hasSize(11);
        DurableFixture.crash(first);

        DirectoryStore recovered = durable.open();

        assertThat(changesOf(recovered)).isEqualTo(before);
        assertThat(changesOf(recovered).get(9)).isInstanceOf(PurgeChange.class);
        recovered.close();
        assertThat(changesOf(durable.open())).as("and after a snapshot file of the recovered store").isEqualTo(before);
    }

    @Test
    void aStoreThatGoesOnAfterRecoveryAppendsToTheSameFeed() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        List<Object> before = changesOf(first);
        first.close();
        DirectoryStore reopened = durable.open();

        Receipt next = durable.replay(reopened, ReferenceStories.rd7Rename(entity(told.parties, ReferenceStories.ACME)));

        List<Object> after = changesOf(reopened);
        assertThat(after).hasSize(10).startsWith(before.toArray());
        assertThat(reopened.changes(new Basis(9), 5)).singleElement().isInstanceOfSatisfying(TransactionChange.class,
                change -> assertThat(change.transaction().basis()).isEqualTo(next.basis()));
    }

    // ------------------------------------------------------------------ the durable store of the run

    @Test
    void aConsumerThatRememberedABasisResumesAfterTheStoreRestartedWithoutMissingOrRepeatingAChange() {
        DurableStore first = durable.openStoreWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        RecordingSubscriber consumer = new RecordingSubscriber(Long.MAX_VALUE);
        first.changeFeed(Basis.NONE).subscribe(consumer);
        List<Change> handled = consumer.next(9);
        Basis remembered = handled.getLast().basis();
        first.close();
        consumer.awaitCompleted();

        DurableStore reopened = durable.openStore();
        Receipt rename = durable.replay(reopened,
                ReferenceStories.rd7Rename(entity(told.parties, ReferenceStories.ACME)));
        RecordingSubscriber resumed = new RecordingSubscriber(Long.MAX_VALUE);
        reopened.changeFeed(remembered).subscribe(resumed);

        assertThat(resumed.next().basis()).isEqualTo(rename.basis());
        assertThat(resumed.pending()).isZero();
        resumed.subscription().cancel();
    }

    @Test
    void aStoreThatIsPurgedAndReopenedGivesTheSameChangesAsBefore() {
        DurableStore first = durable.openStoreWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        first.purge(alicesPurge(told));
        durable.replay(first, ReferenceStories.rd9AcmeLei(entity(told.parties, ReferenceStories.ACME)));
        List<Object> before = changesOf(first);
        first.close();

        DurableStore reopened = durable.openStore();

        assertThat(changesOf(reopened)).hasSize(11).isEqualTo(before);
        reopened.close();
        assertThat(changesOf(durable.openStore())).isEqualTo(before);
    }

    @Test
    void aClosedStoreRefusesToReadAndToFollowChanges() {
        DurableStore store = durable.openStoreWithReferenceModel();
        durable.replay(store, ReferenceStories.rd1Parties());
        store.close();

        assertThatThrownBy(() -> store.changes(Basis.NONE, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.changeFeed(Basis.NONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSubscriptionOfAStoreThatStopsLikeACrashAlsoCompletes() {
        DirectoryStore store = durable.openWithReferenceModel();
        durable.replay(store, ReferenceStories.rd1Parties());
        RecordingSubscriber subscriber = new RecordingSubscriber(0);
        store.changeFeed(Basis.NONE).subscribe(subscriber);

        DurableFixture.crash(store);

        subscriber.awaitCompleted();
        assertThat(subscriber.calls()).isZero();
    }

    @Test
    void aCommitAfterTheStoreIsClosedIsRefusedAndTheClosedFeedStaysClosed() {
        DurableStore store = durable.openStoreWithReferenceModel();
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        store.changeFeed(Basis.NONE).subscribe(subscriber);
        assertThat(subscriber.next().basis()).isEqualTo(new Basis(1));
        store.close();
        subscriber.awaitCompleted();

        assertThatThrownBy(() -> store.commit(Transaction.by("importer").create("Person", from(1, 1),
                values("name", "Bob")).build())).isInstanceOf(IllegalStateException.class);
        assertThat(subscriber.calls()).isEqualTo(1);
    }
}
