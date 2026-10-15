package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.LedgerPurgeTest.entriesOf;
import static io.github.enricoag1982.cairn.core.data.LedgerPurgeTest.mentions;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery writes what the files hold through the same layout as a commit (ADR-0022, ADR-0027, design D6 of
 * {@code refactor-storage-onto-key-value-store}): the log and snapshot files keep their formats, and a store that is
 * opened again holds exactly the keys and values that the store that wrote the files held, whether it recovers from the
 * log alone, from a snapshot file, or from both, and after a purge.
 */
class LedgerDurabilityTest {

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

    @Test
    void aStoreRecoveredFromTheLogAloneHoldsTheKeysThatTheCommitsWrote() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.everyStory(store, durable.clock);
        Map<String, String> written = entriesOf(store.ledger().store());
        assertThat(written).hasSizeGreaterThan(40);
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(entriesOf(recovered.ledger().store())).containsExactlyEntriesOf(written);
    }

    @Test
    void aStoreRecoveredFromASnapshotFileHoldsTheKeysThatTheCommitsWrote() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.everyStory(store, durable.clock);
        Map<String, String> written = entriesOf(store.ledger().store());
        store.close();

        DirectoryStore recovered = durable.open();

        assertThat(entriesOf(recovered.ledger().store())).containsExactlyEntriesOf(written);
    }

    @Test
    void aStoreRecoveredFromASnapshotFileAndTheLogAfterItHoldsTheKeysThatTheCommitsWrote() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, durable.clock);
        store.writeSnapshot();
        EntityId acme = ReferenceTales.entity(told.parties, ACME);
        durable.replay(store, ReferenceStories.rd9AcmeLei(acme));
        durable.replay(store, ReferenceStories.rd7Rename(acme));
        DurableFixture.commit(store, Transaction.by(PRINCIPAL)
                .patch(ReferenceTales.entity(told.accounts, ReferenceStories.A1), from(7, 1),
                        values("number", "A-9")).build());
        Map<String, String> written = entriesOf(store.ledger().store());
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(entriesOf(recovered.ledger().store())).containsExactlyEntriesOf(written);
    }

    @Test
    void aPurgeThatTheFilesRememberLeavesNoKeyOfWhatItPurgedWhenTheStoreIsOpenedAgain() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, durable.clock);
        EntityId acme = ReferenceTales.entity(told.parties, ACME);
        EntityId alice = ReferenceTales.entity(told.parties, ALICE);
        durable.replay(store, ReferenceStories.rd9AcmeLei(acme));
        AttributeId lei = store.schema().current().entityType("Organization").orElseThrow().attribute("lei")
                .orElseThrow().id();
        assertThat(mentions(entriesOf(store.ledger().store()), alice.value())).isTrue();

        assertThat(store.purge(ReferenceStories.rd11PurgeAlice(alice))).isInstanceOf(PurgeResult.Purged.class);
        Map<String, String> purged = entriesOf(store.ledger().store());
        store.close();
        DirectoryStore recovered = durable.open();

        Map<String, String> recoveredEntries = entriesOf(recovered.ledger().store());
        assertThat(recoveredEntries).containsExactlyEntriesOf(purged);
        assertThat(mentions(recoveredEntries, alice.value())).isFalse();
        assertThat(mentions(recoveredEntries, "Alice")).isFalse();
        assertThat(recovered.ledger().uniqueIds(lei, Value.of(LEI), Ledger.LATEST)).containsExactly(acme.value());
    }

    @Test
    void aPurgeThatOnlyTheLogRemembersIsAppliedToTheKeysWhenTheStoreIsOpenedAgain() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, durable.clock);
        EntityId alice = ReferenceTales.entity(told.parties, ALICE);
        store.crashAfterTheNextPurgeIsLogged();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stopped after the purge");
        DirectoryStore recovered = durable.open();

        Map<String, String> entries = entriesOf(recovered.ledger().store());
        assertThat(mentions(entries, alice.value())).isFalse();
        assertThat(recovered.ledger().entity(alice)).isEmpty();
        assertThat(recovered.ledger().entityIds()).doesNotContain(alice).isNotEmpty();
    }
}
