package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.storage.CommitLog;
import io.github.enricoag1982.cairn.core.storage.IdDigests;
import io.github.enricoag1982.cairn.core.storage.PurgeLogRecord;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Purges in a durable store beyond the scenarios of the {@code purge} spec (design D3 and D4 of {@code add-purge}): which
 * files a purge leaves, what recovery restores of purged IDs, keys and records, and what happens when a purge stops half
 * way, cannot be written, or meets a store that is closed.
 */
class PurgeDurableTest {

    /** An ID that no entity has. */
    private static final EntityId UNKNOWN = new EntityId("00000000-0000-7000-8000-0000000000ff");

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

    private static Transaction keyed(String key, Transaction transaction) {
        return new Transaction(transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                Optional.of(key), transaction.writes());
    }

    private static Purge purge(EntityId... entities) {
        Purge.Builder builder = Purge.by("dpo").reason("Erasure request");
        for (EntityId entity : entities) {
            builder.entity(entity);
        }
        return builder.build();
    }

    private static PurgeRecord purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
        return ((PurgeResult.Purged) result).record();
    }

    private static List<Problem> problemsOf(CommitResult result) {
        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    private static List<String> names(List<Path> files) {
        return files.stream().map(path -> path.getFileName().toString()).toList();
    }

    /** RD-1 under a key, RD-3, and a person that nothing else mentions. */
    private record Told(Receipt parties, EntityId alice, EntityId beta, RelationshipId employs, Transaction keyed) {
    }

    private Told tell(DirectoryStore store) {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        Transaction keyedParties = keyed("import-rd1", rd1.transaction());
        Receipt parties = durable.replay(store, new ReferenceStories.Recording(rd1.recordedAt(), keyedParties));
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(durable.replay(store, ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        return new Told(parties, alice, beta, employs, keyedParties);
    }

    private static void copyFiles(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else if (!file.getFileName().toString().equals("lock")) {
                    Files.copy(file, target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the files after a purge

    @Test
    void aPurgeLeavesOneSnapshotFileAtItsBasisAndOneEmptySegmentAfterIt() {
        durable.segmentLimit = 600;
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.writeSnapshot();
        DurableFixture.commit(store, Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build());
        store.writeSnapshot();
        for (String name : List.of("Carol", "Dave", "Erin", "Frank")) {
            DurableFixture.commit(store, Transaction.by("importer").create("Person", from(1, 1), values("name", name))
                    .build());
        }
        assertThat(StoreFiles.segments(directory)).as("the log had several segments").hasSizeGreaterThan(1);
        assertThat(StoreFiles.snapshots(directory)).hasSize(2);

        PurgeRecord record = purged(store.purge(purge(told.alice())));

        assertThat(names(StoreFiles.snapshots(directory))).containsExactly(
                String.format("%020d.snapshot", record.basis().value()));
        assertThat(names(StoreFiles.segments(directory))).containsExactly(
                String.format("%020d.log", record.basis().value() + 1));
        assertThat(StoreFiles.recordStarts(StoreFiles.lastSegment(directory))).as("the new segment has no record")
                .isEmpty();
        assertThat(directory.resolve("snapshots").toFile().list()).noneMatch(name -> name.endsWith(".tmp"));
    }

    @Test
    void theCommitsAfterAPurgeGoToTheNewSegmentAndAreRecoveredAfterACrash() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        PurgeRecord record = purged(store.purge(purge(told.alice())));
        Receipt bob = DurableFixture.commit(store, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build());
        Basis latest = store.latestBasis();
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(latest.value()).isEqualTo(record.basis().value() + 1);
        assertThat(recovered.latestBasis()).isEqualTo(latest);
        assertThat(recovered.purges()).containsExactly(record);
        assertThat(recovered.view(Coordinates.effectiveAt(on(3, 31))).entity((EntityId) bob.created().getFirst()))
                .isPresent();
        assertThat(recovered.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).isEmpty();
        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
    }

    @Test
    void aRejectedPurgeChangesNoFile() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.writeSnapshot();
        Map<String, byte[]> before = StoreFiles.contents(directory);

        PurgeResult result = store.purge(purge(UNKNOWN));

        assertThat(result).isInstanceOf(PurgeResult.Rejected.class);
        assertThat(StoreFiles.contents(directory)).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).as(name).isEqualTo(before.get(name)));
        assertThat(store.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).isPresent();
        assertThat(store.purges()).isEmpty();
    }

    // ------------------------------------------------------------------ what recovery restores

    @Test
    void purgedIdsKeysAndRecordsSurviveClosingAndOpeningAgain() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        PurgeRecord first = purged(store.purge(purge(told.alice())));
        Transaction bob = keyed("bob", Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build());
        Receipt bobReceipt = DurableFixture.commit(store, bob);
        PurgeRecord second = purged(store.purge(Purge.by("dpo").onBehalfOf("DSR-2026-0045").reason("Closed")
                .entity((EntityId) bobReceipt.created().getFirst()).build()));
        store.close();

        DirectoryStore reopened = durable.open();

        assertThat(reopened.purges()).containsExactly(first, second);
        assertThat(reopened.latestBasis()).isEqualTo(second.basis());
        assertThat(problemsOf(reopened.commit(told.keyed()))).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
            assertThat(problem.path()).isEqualTo("@transaction");
        });
        assertThat(problemsOf(reopened.commit(bob))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED));
        Transaction again = Transaction.by("importer").create(told.alice(), "Person", from(1, 1),
                values("name", "Carol")).build();
        assertThat(problemsOf(reopened.commit(again))).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ENTITY_EXISTS);
            assertThat(problem.message()).contains("purged");
        });
    }

    @Test
    void aStoreOpenedWithAGeneratorThatStartsAgainNeverGivesOutAPurgedId() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        Receipt last = DurableFixture.commit(store, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Zoe")).build());
        EntityId zoe = (EntityId) last.created().getFirst();
        purged(store.purge(purge(zoe)));
        store.close();

        DirectoryStore reopened = durable.open();
        Receipt next = DurableFixture.commit(reopened, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Carol")).build());

        assertThat(zoe.value()).isEqualTo(String.format("00000000-0000-7000-8000-%012x", 5));
        assertThat(next.created()).extracting(id -> id.value()).as("the purged ID of Zoe is skipped")
                .containsExactly(String.format("00000000-0000-7000-8000-%012x", 6));
        assertThat(told.alice().value()).isEqualTo(String.format("00000000-0000-7000-8000-%012x", 3));
    }

    @Test
    void aPurgeThatReachedTheLogIsAppliedByRecoveryWithItsIdsKeysAndRecordWithoutASnapshotFile() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.crashAfterTheNextPurgeIsLogged();
        assertThatThrownBy(() -> store.purge(purge(told.alice()))).isInstanceOf(IllegalStateException.class);
        assertThat(StoreFiles.snapshots(directory)).as("the store never wrote a snapshot file").isEmpty();

        DirectoryStore recovered = durable.open();

        assertThat(recovered.purges()).singleElement().satisfies(record -> {
            assertThat(record.entities()).isEqualTo(1);
            assertThat(record.relationships()).isEqualTo(1);
            assertThat(record.basis()).isEqualTo(new Basis(4));
        });
        assertThat(recovered.wasPurged(told.alice())).isTrue();
        assertThat(recovered.wasPurged(told.employs())).isTrue();
        assertThat(recovered.wasPurged(told.beta())).isFalse();
        assertThat(names(StoreFiles.snapshots(directory))).as("recovery wrote a snapshot file at the purge")
                .containsExactly(String.format("%020d.snapshot", 4));
        assertThat(names(StoreFiles.segments(directory))).as("and left only the segment that follows it")
                .containsExactly(String.format("%020d.log", 5));
        assertThat(problemsOf(recovered.commit(told.keyed()))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED));
        assertThat(recovered.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).isEmpty();
        Receipt next = DurableFixture.commit(recovered, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Carol")).build());
        assertThat(next.basis()).isEqualTo(new Basis(5));
        assertThat(next.created()).extracting(id -> id.value())
                .as("Alice's ID is not given out").doesNotContain(told.alice().value());
        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
    }

    @Test
    void aPurgeThatStoppedAfterItsSnapshotFileIsCompletedByDeletingTheOlderFiles() throws IOException {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.writeSnapshot();
        Path before = directory.resolveSibling(directory.getFileName() + "-before");
        Files.createDirectories(before);
        copyFiles(directory, before);
        PurgeRecord record = purged(store.purge(purge(told.alice())));
        DurableFixture.crash(store);
        // The files that the purge deleted last are back, as if the store had stopped before it deleted them.
        for (Path file : StoreFiles.snapshots(before)) {
            Files.copy(file, directory.resolve("snapshots").resolve(file.getFileName()));
        }
        for (Path file : StoreFiles.segments(before)) {
            Files.copy(file, directory.resolve("log").resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(StoreFiles.filesContaining(directory, "Alice")).as("the old files hold Alice").isNotEmpty();

        DirectoryStore reopened = durable.open();

        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
        assertThat(names(StoreFiles.snapshots(directory))).containsExactly(
                String.format("%020d.snapshot", record.basis().value()));
        assertThat(StoreFiles.segments(directory)).hasSize(1);
        assertThat(reopened.purges()).containsExactly(record);
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).isEmpty();
    }

    @Test
    void aPurgeRecordThatNamesWhatTheStoreDoesNotHoldFailsTheOpeningAndChangesNothing() {
        DirectoryStore store = durable.openWithReferenceModel();
        tell(store);
        Basis latest = store.latestBasis();
        DurableFixture.crash(store);
        CommitLog log = CommitLog.open(directory, latest.value() + 1, CommitLog.DEFAULT_SEGMENT_LIMIT);
        log.append(new PurgeLogRecord(new PurgeRecord(new Basis(latest.value() + 1),
                Instant.parse("2027-01-01T00:00:00Z"), "dpo", Optional.empty(), "Erasure request", 1, 0),
                List.of(IdDigests.of(UNKNOWN.value()))));
        log.close();
        Map<String, byte[]> before = StoreFiles.contents(directory);

        assertThatThrownBy(() -> durable.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the record cannot be applied").hasMessageContaining("purge of basis");

        assertThat(StoreFiles.contents(directory)).as("no file was changed").containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).as(name).isEqualTo(before.get(name)));
    }

    @Test
    void aDamagedSnapshotFileAfterAPurgeAndCommitsAfterItIsRefusedToo() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        purged(store.purge(purge(told.alice())));
        DurableFixture.commit(store, Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build());
        DurableFixture.crash(store);
        Path snapshot = StoreFiles.snapshots(directory).getFirst();
        Path segment = StoreFiles.lastSegment(directory);
        assertThat(StoreFiles.recordStarts(segment)).as("the log has the commit after the purge").hasSize(1);
        StoreFiles.damage(snapshot, StoreFiles.size(snapshot) - 10);
        Map<String, byte[]> before = StoreFiles.contents(directory);

        assertThatThrownBy(() -> durable.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(snapshot.toString()).hasMessageContaining(segment.toString())
                .hasMessageContaining("not at basis 1");

        assertThat(StoreFiles.contents(directory)).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).as(name).isEqualTo(before.get(name)));
    }

    @Test
    void aDamagedSnapshotFileAfterAPurgeStillLetsTheOlderOneRecoverAStoreThatWroteTwo() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        purged(store.purge(purge(told.alice())));
        DurableFixture.commit(store, Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build());
        store.writeSnapshot();
        DurableFixture.commit(store, Transaction.by("importer").create("Person", from(1, 1), values("name", "Carol"))
                .build());
        DurableFixture.crash(store);
        assertThat(StoreFiles.snapshots(directory)).hasSize(2);
        Path newest = StoreFiles.snapshots(directory).getLast();
        StoreFiles.damage(newest, StoreFiles.size(newest) - 10);

        DirectoryStore reopened = durable.open();

        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entities("Person")).as("Bob and Carol").hasSize(2);
        assertThat(reopened.purges()).hasSize(1);
        assertThat(reopened.wasPurged(told.alice())).isTrue();
    }

    // ------------------------------------------------------------------ a rule that purges

    /** How long the thread that stands for close() holds the snapshot lock, at most. */
    private static final Duration HOLD = Duration.ofSeconds(8);

    /** A rule of the reference domain's Organization that tries to purge, while holding a lock that close() takes first. */
    private static final class PurgingRule implements EntityRule {

        final AtomicReference<DirectoryStore> store = new AtomicReference<>();
        final AtomicReference<Throwable> outcome = new AtomicReference<>();
        final AtomicReference<Duration> waited = new AtomicReference<>(Duration.ZERO);
        final EntityId target = new EntityId("00000000-0000-7000-8000-0000000000ff");

        @Override
        public RuleDescriptor descriptor() {
            return LeiChecksumRule.DESCRIPTOR;
        }

        @Override
        public void check(EntityState version, RuleContext context) {
            DirectoryStore durableStore = store.get();
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // Another thread holds the snapshot lock, as close() does while it waits for the writer lock, which this
            // thread holds: a purge that took the snapshot lock now would wait for that thread, which waits for this
            // one. The other thread gives up after a few seconds, so that a purge that waits is seen by how long it did.
            Thread closer = new Thread(() -> {
                durableStore.snapshotLock().lock();
                try {
                    holding.countDown();
                    release.await(HOLD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    durableStore.snapshotLock().unlock();
                }
            });
            closer.start();
            long start = System.nanoTime();
            try {
                holding.await();
                durableStore.purge(purge(target));
                outcome.set(new AssertionError("The purge was not refused."));
            } catch (IllegalStateException e) {
                outcome.set(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waited.set(Duration.ofNanos(System.nanoTime() - start));
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(60)
    void aRuleThatPurgesIsRefusedBeforeAnyLockIsTakenAndTheRefusalIsCounted() {
        PurgingRule rule = new PurgingRule();
        DirectoryStore store = durable.open(durable.clock, IdGenerators.deterministic(),
                List.of(rule, new TotalOwnershipRule()), List.of());
        DurableFixture.registerReferenceModel(store);
        rule.store.set(store);

        List<Problem> problems = durable.rejectReplay(store, ReferenceStories.rd1Parties());

        assertThat(rule.outcome.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running rules");
        assertThat(rule.waited.get()).as("the purge is refused at once, not after the lock is free")
                .isLessThan(HOLD.dividedBy(2));
        assertThat(problems).as("a rule that catches the refusal is still reported, for ACME and Beta").hasSize(2)
                .allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED));
        assertThat(store.purges()).isEmpty();
        assertThat(store.snapshotLock().isLocked()).as("no thread holds the snapshot lock").isFalse();
        assertThat(store.writeSnapshot()).isEqualTo(store.latestBasis());
    }

    // ------------------------------------------------------------------ a purge that cannot be made durable

    @Test
    void aPurgeThatCannotBeLoggedIsNotAppliedAndTheStoreRefusesCommits() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.breakLog();

        assertThatThrownBy(() -> store.purge(purge(told.alice()))).isInstanceOf(UncheckedIOException.class);

        assertThat(store.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).as("Alice is still there")
                .isPresent();
        assertThat(store.purges()).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(new Basis(3));
        assertThatThrownBy(() -> store.purge(purge(told.alice()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPurgeWhoseSnapshotFileCannotBeWrittenIsAppliedLoggedAndCompletedAtTheNextOpening() throws IOException {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        Path snapshots = directory.resolve("snapshots");
        Files.createFile(snapshots);

        assertThatThrownBy(() -> store.purge(purge(told.alice()))).isInstanceOf(UncheckedIOException.class);

        assertThat(store.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).as("applied in memory")
                .isEmpty();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()))
                .as("the segments still hold Alice, so the store writes no more to them")
                .isInstanceOf(IllegalStateException.class);
        DurableFixture.crash(store);
        Files.delete(snapshots);

        DirectoryStore reopened = durable.open();

        assertThat(reopened.purges()).hasSize(1);
        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
    }

    @Test
    void aClosedStoreRefusesPurgesAndTheListOfThem() {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        store.close();

        assertThatThrownBy(() -> store.purge(purge(told.alice()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::purges).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPurgeThatWaitedForTheLockWhileTheStoreWasClosedIsRefusedAndRemovesNothing() throws Exception {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        ReentrantLock lock = store.snapshotLock();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        lock.lock();
        Thread waiting = new Thread(() -> {
            try {
                outcome.set(new AssertionError("The purge ran: " + store.purge(purge(told.alice()))));
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
        waiting.start();
        while (!lock.hasQueuedThread(waiting)) {
            Thread.onSpinWait();
        }
        store.close();
        Map<String, byte[]> closed = StoreFiles.contents(directory);
        lock.unlock();
        waiting.join(10_000);

        assertThat(waiting.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat(StoreFiles.contents(directory)).as("nothing was written or deleted after the close")
                .containsOnlyKeys(closed.keySet()).allSatisfy((name, bytes) -> assertThat(bytes)
                        .isEqualTo(closed.get(name)));
    }

    @Test
    void aPurgeWaitsForASnapshotFileThatIsBeingWrittenSoThatNoneIsWrittenAfterItWithThePurgedData() throws Exception {
        DirectoryStore store = durable.openWithReferenceModel();
        Told told = tell(store);
        ReentrantLock lock = store.snapshotLock();
        List<Object> outcome = new ArrayList<>();
        lock.lock();
        Thread waiting = new Thread(() -> outcome.add(store.purge(purge(told.alice()))));
        waiting.start();
        while (!lock.hasQueuedThread(waiting)) {
            Thread.onSpinWait();
        }

        assertThat(outcome).as("the purge waits").isEmpty();
        assertThat(store.view(Coordinates.effectiveAt(on(3, 31))).entity(told.alice())).isPresent();
        lock.unlock();
        waiting.join(10_000);

        assertThat(outcome).singleElement().isInstanceOf(PurgeResult.Purged.class);
        assertThat(StoreFiles.filesContaining(directory, "Alice")).isEmpty();
    }
}
