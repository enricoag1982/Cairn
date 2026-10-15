package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.rocksdb.ReferenceTales.relationship;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stores kept in RocksDB (ADR-0028, design D3 to D5 of {@code add-rocksdb-store}) beyond the scenarios of the specs: what
 * is in the directory, which files hold the data of a commit and of a purge, and stores that readers use while the writer
 * commits or the store is closed.
 */
class RocksDbStoreTest {

    @TempDir
    Path directory;

    private RocksDbFixture rocks;

    @BeforeEach
    void setUp() {
        rocks = new RocksDbFixture(directory);
    }

    @AfterEach
    void tearDown() {
        rocks.closeAll();
    }

    private static ReferenceStories.Recording rd1WithAKey() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        return new ReferenceStories.Recording(rd1.recordedAt(), new Transaction(rd1.transaction().principal(),
                Optional.empty(), Optional.empty(), Optional.of("import-rd1"), rd1.transaction().writes()));
    }

    /** The files of the directory that hold the keys of the database: its sorted tables and its write-ahead logs. */
    private static List<String> dataFilesContaining(Path directory, String text) {
        return StoreFiles.filesContaining(directory, text).stream()
                .filter(name -> name.endsWith(".sst") || name.endsWith(".log")).toList();
    }

    @Test
    void theDirectoryHoldsTheLockOfDirectoryStoresAndTheDatabaseInASubdirectoryOfItsOwn() {
        rocks.openWithReferenceModel();

        assertThat(directory.resolve("lock")).exists();
        assertThat(directory.resolve(RocksDbStores.DATABASE).resolve("CURRENT")).exists();
        assertThat(directory.resolve(RocksDbStores.DATABASE).resolve("LOCK")).exists();
    }

    @Test
    void aCommitIsInTheWriteAheadLogAtOnceAndInADataFileOnceTheStoreWroteASnapshot() {
        DurableStore store = rocks.openWithReferenceModel();
        rocks.replay(store, ReferenceStories.rd1Parties());

        assertThat(dataFilesContaining(directory, "Alice")).as("before").isNotEmpty().allMatch(name -> name.endsWith(".log"));

        Basis snapshot = store.writeSnapshot();

        assertThat(snapshot).isEqualTo(store.latestBasis());
        assertThat(dataFilesContaining(directory, "Alice")).as("after").isNotEmpty().allMatch(name -> name.endsWith(".sst"));
    }

    @Test
    void aPurgeLeavesNoDataFileThatHoldsTheDataAndNoFileThatHoldsTheNameBeforeTheStoreIsClosed() {
        DurableStore store = rocks.openWithReferenceModel();
        Receipt parties = rocks.replay(store, rd1WithAKey());
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(rocks.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        store.writeSnapshot();
        rocks.replay(store, ReferenceStories.rd9AcmeLei(entity(parties, ACME)));
        store.writeSnapshot();

        assertThat(store.purge(ReferenceStories.rd11PurgeAlice(alice))).isInstanceOf(PurgeResult.Purged.class);

        assertThat(StoreFiles.filesContaining(directory, "Alice")).as("the name").isEmpty();
        assertThat(dataFilesContaining(directory, alice.value())).as("her ID in the data files").isEmpty();
        assertThat(dataFilesContaining(directory, employs.value())).as("the relationship's ID").isEmpty();
        assertThat(dataFilesContaining(directory, "ACME")).as("what the purge did not remove stays").isNotEmpty();
    }

    @Test
    void aKeyThatNamedAPurgedEntityIsInNoFileAfterTheStoreIsOpenedAgainAndStaysUnusable() {
        DurableStore store = rocks.openWithReferenceModel();
        Receipt parties = rocks.replay(store, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        rocks.replay(store, ReferenceStories.rd9AcmeLei(acme));
        rocks.clock.moveTo(on(2, 1));
        Transaction renamesAcme = Transaction.by("importer").idempotencyKey("rename-acme")
                .patch(new UniqueKey("Organization", "lei", Value.of(ReferenceStories.LEI)), from(2, 1),
                        values("name", "ACME Holdings")).build();
        Receipt receipt = RocksDbFixture.commit(store, renamesAcme);
        assertThat(receipt.keys()).containsExactly(new KeyResolution(0,
                new UniqueKey("Organization", "lei", Value.of(ReferenceStories.LEI)), acme));
        store.writeSnapshot();
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("the files hold the LEI before")
                .isNotEmpty();

        assertThat(store.purge(Purge.by(ReferenceStories.PURGE_PRINCIPAL).reason(ReferenceStories.PURGE_REASON)
                .entity(acme).build())).isInstanceOf(PurgeResult.Purged.class);
        CommitResult again = store.commit(renamesAcme);

        assertThat(again).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) again).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
            assertThat(problem.path()).isEqualTo("@transaction");
        });
        assertThat(dataFilesContaining(directory, ReferenceStories.LEI)).as("no data file holds the LEI").isEmpty();
        store.close();
        DurableStore reopened = rocks.open();
        assertThat(reopened.commit(renamesAcme)).as("nor does a reopened store accept it again")
                .isInstanceOf(CommitResult.Rejected.class);
        assertThat(StoreFiles.filesContaining(directory, ReferenceStories.LEI)).as("no file holds the LEI")
                .isEmpty();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(acme)).isEmpty();
    }

    @Test
    void aPurgeThatReturnedIsInEveryFileOfNoneAfterAProcessStoppedWithoutClosingTheStore() throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("cairn.testRuntimeClasspath", System.getProperty("java.class.path")),
                CrashedProcess.class.getName(), directory.toString(), "purge")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the process stopped by itself").isTrue();
        assertThat(process.exitValue()).isEqualTo(CrashedProcess.HALTED);
        assertThat(StoreFiles.filesContaining(directory, "Alice")).as("the files after the crash").isEmpty();

        DurableStore reopened = rocks.open();

        assertThat(reopened.purges()).singleElement().satisfies(record -> {
            assertThat(record.entities()).isEqualTo(1);
            assertThat(record.relationships()).isEqualTo(1);
        });
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Person")).as("Alice is gone").isEmpty();
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Organization")).hasSize(2);
        assertThat(StoreFiles.filesContaining(directory, "Alice")).as("the files after reopening").isEmpty();
    }

    @Test
    void readersGoOnWhileTheWriterCommitsAndPurges() throws Exception {
        DurableStore store = rocks.openWithReferenceModel();
        ExecutorService readers = Executors.newFixedThreadPool(3);
        AtomicBoolean writing = new AtomicBoolean(true);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int reader = 0; reader < 3; reader++) {
                results.add(readers.submit(() -> {
                    int reads = 0;
                    while (writing.get()) {
                        int people = store.view(Coordinates.effectiveAt(on(2, 1))).entities("Person").size();
                        assertThat(people).as("people that a view reads").isBetween(0, 40);
                        reads++;
                    }
                    return reads;
                }));
            }

            List<EntityId> created = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                created.add((EntityId) RocksDbFixture.commit(store, Transaction.by("importer")
                        .create("Person", from(1, 1), values("name", "Person " + i)).build()).created().getFirst());
            }
            Purge purge = Purge.by("dpo").reason("erasure").entity(created.getFirst()).build();
            assertThat(store.purge(purge)).isInstanceOf(PurgeResult.Purged.class);
            writing.set(false);

            for (Future<Integer> result : results) {
                assertThat(result.get(1, TimeUnit.MINUTES)).isPositive();
            }
        } finally {
            writing.set(false);
            readers.shutdownNow();
        }
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).hasSize(39);
    }

    @Test
    void aViewThatIsReadAfterTheStoreWasClosedFailsInsteadOfCrashing() {
        DurableStore store = rocks.openWithReferenceModel();
        rocks.replay(store, ReferenceStories.rd1Parties());
        var view = store.view(Coordinates.effectiveAt(on(2, 1)));
        store.close();

        assertThatThrownBy(() -> view.entities("Person")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDirectoryThatIsNotEmptyOfOtherFilesStillOpens() throws IOException {
        Files.writeString(directory.resolve("README.txt"), "kept");

        DurableStore store = rocks.openWithReferenceModel();

        assertThat(store.latestBasis()).isEqualTo(new Basis(1));
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(file -> file.getFileName().toString())).contains("README.txt", "lock",
                    RocksDbStores.DATABASE);
        }
    }
}
