package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PURGE_PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PURGE_REASON;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.relationship;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec;
import io.github.enricoag1982.cairn.core.storage.StoredMetadata;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeKey;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scenarios of the {@code purge} spec that concern RocksDB stores, one test each. */
class RocksDbPurgeScenariosTest {

    private static final String ALICES_NAME = "Alice";

    @TempDir
    Path directory;

    private RocksDbFixture rocks;
    /** The directory stores that a test opened besides, which {@link #tearDown()} closes. */
    private final List<DurableStore> directoryStores = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rocks = new RocksDbFixture(directory);
    }

    @AfterEach
    void tearDown() {
        directoryStores.forEach(DurableStore::close);
        rocks.closeAll();
    }

    /** Opens a durable store kept in a directory, with the rules of the reference domain, and keeps it to be closed. */
    private DurableStore openDirectoryStore(Path files, TestClock clock) {
        DurableStore store = Stores.durable(files, StoreSettings.builder().clock(clock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).build());
        directoryStores.add(store);
        return store;
    }

    /**
     * RD-1 and RD-9 are committed to a store that {@code open} opens, ACME is purged by the key of its LEI, and the store
     * is closed and opened again: no file in its directory holds the LEI at any time after the purge, and the store lists
     * a purge record of one entity.
     */
    private void purgeAcmeByItsLeiAndLookForTheKey(String kind, Path files, TestClock clock,
            Supplier<DurableStore> open) {
        UniqueKey acmeLei = new UniqueKey("Organization", "lei", Value.of(LEI));
        DurableStore store = open.get();
        RocksDbFixture.registerReferenceModel(store);
        EntityId acme = entity(ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties()), ACME);
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        assertThat(StoreFiles.filesContaining(files, LEI)).as(kind + ": the files hold the LEI before the purge")
                .isNotEmpty();

        PurgeResult result = store.purge(Purge.by(PURGE_PRINCIPAL).reason(PURGE_REASON).entity(acmeLei).build());

        assertThat(result).as(kind).isInstanceOf(PurgeResult.Purged.class);
        assertThat(((PurgeResult.Purged) result).keys()).as(kind + ": the key named ACME")
                .containsExactly(new PurgeResult.ResolvedKey(new PurgeKey(acmeLei, Optional.empty()), acme));
        assertThat(StoreFiles.filesContaining(files, LEI)).as(kind + ": the files after the purge").isEmpty();
        store.close();
        DurableStore reopened = open.get();

        assertThat(StoreFiles.filesContaining(files, LEI)).as(kind + ": the files after reopening").isEmpty();
        assertThat(StoreFiles.contents(files)).as(kind + ": the store has files").isNotEmpty();
        assertThat(reopened.purges()).as(kind + ": a purge record of one entity").singleElement()
                .satisfies(record -> {
                    assertThat(record.entities()).isEqualTo(1);
                    assertThat(record.relationships()).isZero();
                });
        assertThat(reopened.wasPurged(acme)).isTrue();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(acme)).isEmpty();
    }

    /** RD-1 with an idempotency key, which wrote Alice. */
    private static ReferenceStories.Recording rd1WithAKey() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        return new ReferenceStories.Recording(rd1.recordedAt(), new Transaction(rd1.transaction().principal(),
                Optional.empty(), Optional.empty(), Optional.of("import-rd1"), rd1.transaction().writes()));
    }

    private static void assertNoAlice(DurableStore store, EntityId alice, RelationshipId employs) {
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
    @DisplayName("Scenario: Nothing left in a RocksDB store's files")
    void nothingLeftInARocksDbStoresFiles() {
        DurableStore store = rocks.openWithReferenceModel();
        Receipt parties = rocks.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(rocks.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files hold Alice before the purge")
                .isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("and her ID as text").isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).isNotEmpty();

        assertThat(store.purge(ReferenceStories.rd11PurgeAlice(alice))).isInstanceOf(PurgeResult.Purged.class);

        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after the purge").isEmpty();
        store.close();
        DurableStore reopened = rocks.open();

        assertNoAlice(reopened, alice, employs);
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after reopening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("her ID as text, after reopening")
                .isEmpty();
        assertThat(StoreFiles.filesContaining(directory, employs.value())).as("the ID of the relationship").isEmpty();
        assertThat(reopened.wasPurged(alice)).as("the reopened store knows that her ID was purged").isTrue();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Organization")).as("ACME and Beta stay")
                .hasSize(2);
        assertThat(reopened.purges()).singleElement().satisfies(record -> {
            assertThat(record.entities()).isEqualTo(1);
            assertThat(record.relationships()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Scenario: Nothing of a purge left in an open RocksDB store's files")
    void nothingOfAPurgeLeftInAnOpenRocksDbStoresFiles() {
        DurableStore store = rocks.openWithReferenceModel();
        Receipt parties = rocks.replay(store, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        rocks.replay(store, ReferenceStories.rd9AcmeLei(acme));
        assertThat(acme.value()).as("ACME has the lowest ID, so its keys are the bounds of the files")
                .isEqualTo("00000000-0000-7000-8000-000000000001");
        // The ID as text, and as the ledger's keys encode it: the text with its length in front.
        byte[] idText = acme.value().getBytes(StandardCharsets.UTF_8);
        byte[] currentKey = LedgerCodec.currentKey(acme.value());
        byte[] idInKeys = Arrays.copyOfRange(currentKey, 1, currentKey.length);
        assertThat(StoreFiles.filesContaining(directory, idText)).as("the files hold the ID before the purge")
                .isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, idInKeys)).as("encoded as a key").isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("and the LEI").isNotEmpty();

        assertThat(store.purge(ReferenceStories.rd11PurgeAlice(acme))).isInstanceOf(PurgeResult.Purged.class);

        assertThat(StoreFiles.filesContaining(directory, idText)).as("the ID as text, in the open store's files")
                .isEmpty();
        assertThat(StoreFiles.filesContaining(directory, idInKeys)).as("the ID as the ledger's keys encode it")
                .isEmpty();
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("the LEI").isEmpty();
        assertThat(store.wasPurged(acme)).isTrue();
        assertThat(StoreFiles.contents(directory).keySet()).as("the old MANIFEST is gone")
                .filteredOn(name -> name.contains("MANIFEST")).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: No trace of a key in a store's files")
    void noTraceOfAKeyInAStoresFiles() {
        Path directoryFiles = directory.resolve("directory-store");
        TestClock directoryClock = new TestClock();
        purgeAcmeByItsLeiAndLookForTheKey("a store kept in a directory", directoryFiles, directoryClock,
                () -> openDirectoryStore(directoryFiles, directoryClock));

        Path rocksFiles = directory.resolve("rocksdb-store");
        RocksDbFixture rocksFixture = new RocksDbFixture(rocksFiles);
        try {
            purgeAcmeByItsLeiAndLookForTheKey("a RocksDB store", rocksFiles, rocksFixture.clock, rocksFixture::open);
        } finally {
            rocksFixture.closeAll();
        }
    }

    @Test
    @DisplayName("Scenario: A purge interrupted in a RocksDB store")
    void aPurgeInterruptedInARocksDbStore() {
        DurableStore store = rocks.openStopping(StoppingKeyValueStore.Stop.BEFORE_FLUSH);
        RocksDbFixture.registerReferenceModel(store);
        Receipt parties = rocks.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(rocks.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        // Alice is in a data file as well as in the log: only a compaction takes her out of the data file.
        store.writeSnapshot();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);
        store.close();

        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME))
                .as("the purge was written, but the files still hold Alice, as they do when the process stops")
                .isNotEmpty();
        DurableStore reopened = rocks.open();

        assertNoAlice(reopened, alice, employs);
        assertThat(StoreFiles.filesContaining(directory, ALICES_NAME)).as("the files after opening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, alice.value())).as("her ID as text, after opening")
                .isEmpty();
        assertThat(reopened.purges()).singleElement().satisfies(record -> {
            assertThat(record.entities()).isEqualTo(1);
            assertThat(record.relationships()).isEqualTo(1);
        });
        assertThat(reopened.wasPurged(alice)).isTrue();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Organization")).as("ACME and Beta stay")
                .hasSize(2);
    }

    @Test
    void aPurgeThatStoppedAfterItFlushedButBeforeItCompactedIsFinishedWhenTheStoreIsOpened() {
        DurableStore store = rocks.openStopping(StoppingKeyValueStore.Stop.BEFORE_COMPACTION);
        RocksDbFixture.registerReferenceModel(store);
        Receipt parties = rocks.replay(store, rd1WithAKey());
        EntityId acme = entity(parties, ACME);
        rocks.replay(store, ReferenceStories.rd9AcmeLei(acme));
        store.writeSnapshot();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(acme)))
                .isInstanceOf(UncheckedIOException.class);
        store.close();

        assertThat(StoreFiles.filesContaining(directory, acme.value())).as("the files still hold the ID").isNotEmpty();
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("and the LEI").isNotEmpty();
        DurableStore reopened = rocks.open();

        assertThat(reopened.wasPurged(acme)).isTrue();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(acme)).isEmpty();
        assertThat(StoreFiles.filesContaining(directory, acme.value())).as("the ID after opening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("the LEI after opening").isEmpty();
        assertThat(StoreFiles.filesContaining(directory, "ACME")).isEmpty();
    }

    @Test
    void aStoreThatFinishedAPurgeWhenItWasOpenedHasNoNoteLeftAndOpensQuicklyAfterwards() {
        DurableStore store = rocks.openStopping(StoppingKeyValueStore.Stop.BEFORE_FLUSH);
        RocksDbFixture.registerReferenceModel(store);
        Receipt parties = rocks.replay(store, rd1WithAKey());
        EntityId alice = entity(parties, ALICE);
        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);
        store.close();
        rocks.open().close();

        try (RocksDbKeyValueStore database = RocksDbKeyValueStore.open(directory.resolve(RocksDbStores.DATABASE),
                () -> { })) {
            assertThat(StoredMetadata.isCompactionPending(database)).isFalse();
        }
    }
}
