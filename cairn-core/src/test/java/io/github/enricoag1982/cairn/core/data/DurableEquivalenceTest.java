package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
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
 * Design D8 of {@code add-durable-commit-log}: a durable store and an in-memory store, with the same clock and the same
 * deterministic ID generator, commit every story of the reference domain, and are compared before the durable store is
 * reopened, after it is closed and opened again, and after a store that was abandoned is opened again.
 */
class DurableEquivalenceTest {

    @TempDir
    Path directory;

    private DurableFixture durable;
    private final TestClock memoryClock = new TestClock();
    private Store memory;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
        memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build());
        DurableFixture.registerReferenceModel(memory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    /** A transaction with an idempotency key, which both stores commit after the stories. */
    private static Transaction keyed(Receipt parties) {
        Id alice = parties.temporaryIds().get(ALICE);
        return Transaction.by(ReferenceStories.PRINCIPAL).idempotencyKey("alice-birth-date")
                .reason("The birth date was missing").onBehalfOf("hr")
                .patch((EntityId) alice, from(1, 1),
                        values("birthDate", LocalDate.of(1990, 5, 17))).build();
    }

    private static StoreEquivalence.Observation observe(Store store, ReferenceTales.Told told,
            List<Transaction> keyed) {
        return StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, keyed);
    }

    @Test
    void aDurableStoreIsEquivalentToAnInMemoryStoreBeforeAndAfterReopening() {
        DirectoryStore store = durable.openWithReferenceModel();

        ReferenceTales.Told inMemory = ReferenceTales.everyStory(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.everyStory(store, durable.clock);
        Transaction keyed = keyed(inMemory.parties);
        List<Transaction> keys = List.of(keyed);
        DurableFixture.commit(memory, keyed);
        DurableFixture.commit(store, keyed);

        assertThat(onDisk.receipts).as("the receipts of the accepted stories").isEqualTo(inMemory.receipts);
        assertThat(onDisk.rejected).as("the problems of the rejected stories").isEqualTo(inMemory.rejected);
        assertThat(onDisk.rejected).as("four stories are rejected").hasSize(4);
        StoreEquivalence.Observation expected = observe(memory, inMemory, keys);
        StoreEquivalence.assertEquivalent(expected, observe(store, onDisk, keys));

        store.close();
        DirectoryStore reopened = durable.open();
        StoreEquivalence.assertEquivalent(expected, observe(reopened, onDisk, keys));

        // Both stores go on: the reopened store gets the same bases, transaction times and IDs as the other.
        Transaction more = Transaction.by(ReferenceStories.PRINCIPAL)
                .create("Person", from(1, 1), values("name", "Bob")).build();
        Receipt inMemoryMore = DurableFixture.commit(memory, more);
        Receipt reopenedMore = DurableFixture.commit(reopened, more);
        assertThat(reopenedMore).isEqualTo(inMemoryMore);
        inMemory.receipts.add(inMemoryMore);
        onDisk.receipts.add(reopenedMore);
        StoreEquivalence.assertEquivalent(observe(memory, inMemory, keys), observe(reopened, onDisk, keys));

        // A snapshot file in the middle, and a store that is abandoned: the snapshot file and the log after it recover it.
        reopened.writeSnapshot();
        Transaction last = Transaction.by(ReferenceStories.PRINCIPAL).reason("after the snapshot")
                .create("Person", from(2, 1), values("name", "Carol")).build();
        inMemory.receipts.add(DurableFixture.commit(memory, last));
        onDisk.receipts.add(DurableFixture.commit(reopened, last));
        StoreEquivalence.Observation beforeCrash = observe(memory, inMemory, keys);
        StoreEquivalence.assertEquivalent(beforeCrash, observe(reopened, onDisk, keys));
        DurableFixture.crash(reopened);

        DirectoryStore recovered = durable.open();
        StoreEquivalence.assertEquivalent(beforeCrash, observe(recovered, onDisk, keys));
        assertThat(recovered.view(Coordinates.effectiveAt(on(3, 1)))
                .entities("Person")).hasSize(3);
    }

    @Test
    void anAbandonedStoreWithoutASnapshotFileIsRecoveredFromTheLogAlone() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told inMemory = ReferenceTales.everyStory(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.everyStory(store, durable.clock);
        StoreEquivalence.Observation expected = observe(memory, inMemory, List.of());
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(StoreFiles.snapshots(directory)).isEmpty();
        StoreEquivalence.assertEquivalent(expected, observe(recovered, onDisk, List.of()));
    }

    @Test
    void theStoriesAreRecoveredFromLogSegmentsOfASmallSize() {
        durable.segmentLimit = 2_000;
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told inMemory = ReferenceTales.everyStory(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.everyStory(store, durable.clock);
        StoreEquivalence.Observation expected = observe(memory, inMemory, List.of());
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(StoreFiles.segments(directory)).as("the log has several segments").hasSizeGreaterThan(3);
        StoreEquivalence.assertEquivalent(expected, observe(recovered, onDisk, List.of()));
    }

    @Test
    void theEquivalenceHelperSeesADifference() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told inMemory = ReferenceTales.rd1ToRd5(memory, memoryClock);
        ReferenceTales.Told onDisk = ReferenceTales.rd1ToRd5(store, durable.clock);
        StoreEquivalence.Observation same = observe(memory, inMemory, List.of());
        StoreEquivalence.assertEquivalent(same, observe(store, onDisk, List.of()));
        assertThat(same.views()).as("what is compared").hasSizeGreaterThan(1_000);

        DurableFixture.commit(store, Transaction.by(ReferenceStories.PRINCIPAL)
                .patch((EntityId) onDisk.parties.temporaryIds().get(ACME),
                        from(1, 1), values("name", "ACME Ltd")).build());

        assertThatThrownBy(() -> StoreEquivalence.assertEquivalent(same, observe(store, onDisk, List.of())))
                .isInstanceOf(AssertionError.class);
    }
}
