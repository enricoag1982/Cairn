package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the {@code purge} spec that concern durable stores, one test each: nothing is left on disk after a
 * purge, and a purge that was interrupted after it reached the log is completed when the store is opened.
 */
class PurgeDurabilityScenariosTest {

    private static final String ALICES_NAME = "Alice";

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

    /** RD-1 with an idempotency key, which wrote Alice, and then RD-3, which relates her to Beta. */
    private static ReferenceStories.Recording rd1WithAKey() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        return new ReferenceStories.Recording(rd1.recordedAt(), new Transaction(rd1.transaction().principal(),
                Optional.empty(), Optional.empty(), Optional.of("import-rd1"), rd1.transaction().writes()));
    }

    private static PurgeResult.Purged purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
        return (PurgeResult.Purged) result;
    }

    private static void assertNoAlice(DirectoryStore store, EntityId alice, RelationshipId employs) {
        for (long basis = 0; basis <= store.latestBasis().value(); basis++) {
            View view = store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(new Basis(basis)));
            assertThat(view.entity(alice)).as("Alice at basis " + basis).isEmpty();
            assertThat(view.timeline(alice)).isEmpty();
            assertThat(view.relationship(employs)).isEmpty();
            if (view.schema().entityType("Person").isPresent()) {
                assertThat(view.entities("Person")).extracting(EntityState::id).doesNotContain(alice);
            }
        }
    }

    @Test
    @DisplayName("Scenario: Nothing left on disk")
    void nothingLeftOnDisk() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(durable.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        store.writeSnapshot();
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files hold Alice before the purge")
                .isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("and her ID as text").isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).isNotEmpty();

        purged(store.purge(ReferenceStories.rd11PurgeAlice(alice)));

        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after the purge").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("her ID as text").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).as("the ID of the relationship").isEmpty();
        store.close();
        DirectoryStore reopened = durable.open();

        assertNoAlice(reopened, alice, employs);
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after reopening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("her ID as text, after reopening")
                .isEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).isEmpty();
        assertThat(reopened.wasPurged(alice)).as("the reopened store knows that her ID was purged").isTrue();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Organization")).as("ACME and Beta stay")
                .hasSize(2);
    }

    @Test
    @DisplayName("Scenario: A purge interrupted")
    void aPurgeInterrupted() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(durable.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        store.writeSnapshot();
        Basis beforePurge = store.latestBasis();
        store.crashAfterTheNextPurgeIsLogged();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stopped after the purge");

        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME))
                .as("the purge reached the log, but the files still hold Alice").isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("and her ID").isNotEmpty();
        DirectoryStore reopened = durable.open();

        assertNoAlice(reopened, alice, employs);
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after opening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("her ID as text, after opening")
                .isEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).isEmpty();
        assertThat(reopened.purges()).containsExactly(new PurgeRecord(new Basis(beforePurge.value() + 1),
                reopened.purges().getFirst().transactionTime(), ReferenceStories.PURGE_PRINCIPAL, Optional.empty(),
                ReferenceStories.PURGE_REASON, 1, 1));
        assertThat(reopened.wasPurged(alice)).isTrue();
        assertThat(reopened.wasPurged(employs)).isTrue();
        assertThat(reopened.wasPurged(beta)).isFalse();
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(beforePurge.value() + 1));
    }

    @Test
    @DisplayName("Scenario: A damaged snapshot file after a purge")
    void aDamagedSnapshotFileAfterAPurge() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        durable.replay(store, ReferenceStories.rd3Employment(beta, alice));
        purged(store.purge(ReferenceStories.rd11PurgeAlice(alice)));
        DurableFixture.crash(store);
        Path snapshot = StoreFiles.snapshots(directory).getFirst();
        assertThat(StoreFiles.snapshots(directory)).as("the purge left one snapshot file").hasSize(1);
        StoreFiles.damage(snapshot, StoreFiles.size(snapshot) - 10);
        Map<String, byte[]> before = StoreFiles.contents(directory);

        assertThatThrownBy(() -> durable.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(snapshot.toString());

        assertThat(StoreFiles.contents(directory)).as("the files are unchanged").containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).as(name).isEqualTo(before.get(name)));
    }
}
