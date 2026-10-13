package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.CommitLog;
import io.github.enricoag1982.cairn.core.storage.DirectoryLock;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.PurgeFiles;
import io.github.enricoag1982.cairn.core.storage.PurgeLogRecord;
import io.github.enricoag1982.cairn.core.storage.Recovery;
import io.github.enricoag1982.cairn.core.storage.Snapshots;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A store kept in a directory (ADR-0011, ADR-0022, design of {@code add-durable-commit-log}): an {@link InMemoryStore}
 * with a commit log, so that the store and the in-memory store cannot drift apart. Use {@code Stores.durable} to open
 * one.
 *
 * <p>Opening takes the directory's lock, recovers the state from the newest valid snapshot file and the log after it, lets
 * the sequence of bases and transaction times continue after the last recovered commit, and starts a new log segment.
 * From then on every commit and schema registration is written to the log and forced to disk before it is recorded and
 * published. {@link #close()} writes a snapshot file and releases the directory.
 *
 * <p>A purge (ADR-0024, design D4 of {@code add-purge}) is written to the log and forced to disk before it is applied.
 * Then, still holding the writer lock, the store writes a snapshot file at the purge's basis, deletes every older snapshot
 * file and every log segment, and starts a new segment, so that no file holds the purged data when the purge returns.
 * Opening a directory whose log holds a purge that no snapshot file holds completes the purge in the same way before the
 * store opens, and also deletes the files that a purge left behind if it stopped after writing its snapshot file.
 *
 * <p>After {@code close}, every call but {@code close} throws an {@code IllegalStateException}, on the store and on its
 * schema registry. After a failed write, commits and registrations throw one, while reads and dry runs go on.
 */
public final class DirectoryStore implements DurableStore {

    private final Path directory;
    private final InMemoryStore store;
    private final CommitLog log;
    private final DirectoryLock lock;
    private final SchemaRegistry registry;
    /** Serializes snapshot files, and closing; the writer lock is not needed to write a snapshot. */
    private final ReentrantLock snapshotLock = new ReentrantLock();
    private volatile boolean closed;
    /** Set by a test: stop right after the next purge reached the log. */
    private volatile boolean crashAfterPurgeIsLogged;

    private DirectoryStore(Path directory, InMemoryStore store, CommitLog log, DirectoryLock lock) {
        this.directory = directory;
        this.store = store;
        this.log = log;
        this.lock = lock;
        this.registry = new Registry();
    }

    /**
     * Opens the store in a directory, which is created if it does not exist. A new or empty directory gives an empty
     * store; a directory that a store wrote gives that store's state.
     *
     * @param directory     the directory
     * @param clock         the source of transaction times
     * @param generator     the ID generator of the store, which the store owns from now on
     * @param rules         the rules with code, which the store runs at commit
     * @param declaredRules the descriptions of rules that the store has no code for
     * @return the open store
     * @throws UncheckedIOException     if reading or writing the directory fails
     * @throws IllegalStateException    if another open store holds the directory, a file is damaged anywhere but at the
     *                                  end of the last log segment, a file was written in a format version that is not
     *                                  known, or the generator does not accept the ID of an entity or a relationship that
     *                                  the store holds; the message names the file and, for damage, the position, or
     *                                  the ID
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two rules
     *                                  have the same name and version
     */
    public static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules) {
        return open(directory, clock, generator, rules, declaredRules, CommitLog.DEFAULT_SEGMENT_LIMIT);
    }

    /** Opens a store whose log segments are complete after {@code segmentLimit} bytes; for tests of segments. */
    static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules, long segmentLimit) {
        Objects.requireNonNull(directory, "directory");
        InMemoryStore store = new InMemoryStore(clock, generator, rules, declaredRules);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Creating the directory " + directory + " failed.", e);
        }
        DirectoryLock lock = DirectoryLock.acquire(directory);
        CommitLog log = null;
        boolean opened = false;
        try {
            Recovery.Result recovered = Recovery.recover(directory, new Restorer(store));
            requireIdsOfTheGenerator(store, generator);
            store.underWriterLock(() -> {
                store.sequence().resume(recovered.latest(), recovered.lastTransactionTime());
                return null;
            });
            completePurge(directory, store, recovered);
            log = CommitLog.open(directory, recovered.latest().value() + 1, segmentLimit);
            DirectoryStore result = new DirectoryStore(directory, store, log, lock);
            store.attach(result.new Guard());
            opened = true;
            return result;
        } finally {
            if (!opened) {
                try {
                    if (log != null) {
                        log.close();
                    }
                } finally {
                    lock.close();
                }
            }
        }
    }

    /**
     * Checks that the ID generator accepts every ID that the recovered store holds, as an entity or a relationship: a
     * store keeps the ID generator it was created with, so one that does not accept them is another generator.
     *
     * @throws IllegalStateException naming one ID that the generator does not accept
     */
    private static void requireIdsOfTheGenerator(InMemoryStore store, IdGenerator generator) {
        for (EntityId id : store.entities().keySet()) {
            if (!generator.accepts(id.value())) {
                throw otherGenerator(id.value(), "an entity");
            }
        }
        for (RelationshipId id : store.relationships().keySet()) {
            if (!generator.accepts(id.value())) {
                throw otherGenerator(id.value(), "a relationship");
            }
        }
    }

    private static IllegalStateException otherGenerator(String id, String holder) {
        return new IllegalStateException("The ID generator that the store is opened with does not accept the ID " + id
                + " of " + holder + " that the store holds. A store keeps the ID generator it was created with, so open "
                + "it with that one.");
    }

    /**
     * Completes a purge that was interrupted, before the store opens (design D4 of {@code add-purge}). A purge record in
     * the log is newer than the newest valid snapshot file, so the store, which has applied the record, writes a snapshot
     * file of its state, and every older snapshot file and every log segment goes. If the newest snapshot file already
     * holds the purge, which is the case when the purge stopped after writing it, the older files that remain go.
     */
    private static void completePurge(Path directory, InMemoryStore store, Recovery.Result recovered) {
        if (recovered.lastPurge().isEmpty()) {
            return;
        }
        try {
            long basis = recovered.lastPurge().get().value();
            if (recovered.purgePending()) {
                Snapshots.Content content = StoreImage.of(store);
                Snapshots.write(directory, content);
                basis = content.basis().value();
            }
            PurgeFiles.deleteOlder(directory, basis);
        } catch (IOException e) {
            throw new UncheckedIOException("Completing the purge in " + directory + " failed.", e);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The store is closed.");
        }
    }

    @Override
    public SchemaRegistry schema() {
        requireOpen();
        return registry;
    }

    @Override
    public CommitResult commit(Transaction transaction) {
        requireOpen();
        return store.commit(transaction);
    }

    @Override
    public DryRunResult dryRun(Transaction transaction) {
        requireOpen();
        return store.dryRun(transaction);
    }

    @Override
    public View view(Coordinates coordinates) {
        requireOpen();
        return store.view(coordinates);
    }

    @Override
    public Basis latestBasis() {
        requireOpen();
        return store.latestBasis();
    }

    @Override
    public List<RuleDescriptor> declaredRules() {
        requireOpen();
        return store.declaredRules();
    }

    @Override
    public List<TransactionRecord> transactions() {
        requireOpen();
        return store.transactions();
    }

    @Override
    public PurgeResult purge(Purge purge) {
        // A thread that holds the writer lock, such as a rule that purges, must not take the snapshot lock after it, which
        // close() takes first: refuse it before any lock is taken.
        store.refuseWriterLockHolder();
        // The snapshot lock first, as close() takes it, so that no snapshot file is being written while the purge removes
        // the data, and none is written after it that holds the data: the writer lock comes second.
        snapshotLock.lock();
        try {
            requireOpen();
            return store.purge(purge, this::makePurgeDurable);
        } finally {
            snapshotLock.unlock();
        }
    }

    @Override
    public List<PurgeRecord> purges() {
        requireOpen();
        return store.purges();
    }

    @Override
    public boolean wasPurged(Id id) {
        requireOpen();
        return store.wasPurged(id);
    }

    /**
     * Makes a purge that was logged and applied durable: writes a snapshot file at its basis, deletes the older snapshot
     * files and every log segment, and starts a new segment. The caller holds the snapshot lock and the writer lock. If it
     * fails, the log refuses further commits, because the segments that remain hold the purged data, and opening the
     * directory again completes the purge.
     */
    private void makePurgeDurable() {
        try {
            Snapshots.Content content = StoreImage.of(store);
            Snapshots.write(directory, content);
            PurgeFiles.deleteSnapshotsBefore(directory, content.basis().value());
            log.discardAndRestart(content.basis().value() + 1);
        } catch (IOException e) {
            log.fail();
            throw new UncheckedIOException("Removing the purged data from the files in " + directory + " failed.", e);
        } catch (RuntimeException e) {
            log.fail();
            throw e;
        }
    }

    @Override
    public Basis writeSnapshot() {
        snapshotLock.lock();
        try {
            // Closing holds this lock while it writes its own snapshot and releases the directory, so the store can
            // have been closed while this call waited for the lock.
            requireOpen();
            return snapshot();
        } finally {
            snapshotLock.unlock();
        }
    }

    /**
     * Writes a snapshot file of the latest state and deletes what the two newest valid snapshot files make unnecessary.
     * The caller holds the snapshot lock.
     */
    private Basis snapshot() {
        try {
            Snapshots.Content content = StoreImage.of(store);
            Snapshots.write(directory, content);
            Snapshots.prune(directory, content.basis().value());
            return content.basis();
        } catch (IOException e) {
            throw new UncheckedIOException("Writing a snapshot file in " + directory + " failed.", e);
        }
    }

    @Override
    public void close() {
        snapshotLock.lock();
        try {
            if (closed) {
                return;
            }
            // Taking the writer lock lets a commit that is running finish, and refuses the ones that follow.
            store.underWriterLock(() -> {
                closed = true;
                return null;
            });
            try {
                snapshot();
            } finally {
                release();
            }
        } finally {
            snapshotLock.unlock();
        }
    }

    /** Closes the log and releases the directory, whatever fails. */
    private void release() {
        try {
            log.close();
        } finally {
            lock.close();
        }
    }

    /**
     * Abandons the store as a crash does: nothing is written, no snapshot file either, but the lock is released, so that
     * another store can be opened on the directory in this JVM. For tests of recovery after a crash.
     */
    void abandon() {
        snapshotLock.lock();
        try {
            if (closed) {
                return;
            }
            store.underWriterLock(() -> {
                closed = true;
                return null;
            });
            release();
        } finally {
            snapshotLock.unlock();
        }
    }

    /** The lock that serializes snapshot files and closing; for tests of what happens while it is held. */
    ReentrantLock snapshotLock() {
        return snapshotLock;
    }

    /** Makes the next write to the log fail, as a failing disk would. For tests of a commit that cannot be written. */
    void breakLog() {
        log.breakChannel();
    }

    /**
     * Makes the store stop as a crash does right after the next purge was written to the log and forced to disk, before
     * the purge is applied and before the snapshot file is written: the store is abandoned, its directory is released,
     * and the call that purges throws. For tests of a purge that was interrupted.
     */
    void crashAfterTheNextPurgeIsLogged() {
        crashAfterPurgeIsLogged = true;
    }

    /**
     * The journal of the in-memory store: the commit log, which a closed store does not write to. The store and its
     * registry call it holding the writer lock, which {@link #close()} takes to close the store, so a commit is either
     * finished before the store is closed or refused.
     */
    private final class Guard implements Journal {

        @Override
        public void requireWritable() {
            requireOpen();
            log.requireWritable();
        }

        @Override
        public void append(LogRecord record) {
            requireWritable();
            log.append(record);
            if (crashAfterPurgeIsLogged && record instanceof PurgeLogRecord) {
                crashAfterPurgeIsLogged = false;
                abandon();
                throw new IllegalStateException("The store stopped after the purge reached the log, as a test asked.");
            }
        }
    }

    /** The store's schema registry, which refuses every call once the store is closed. */
    private final class Registry implements SchemaRegistry {

        @Override
        public RegistrationResult register(Origin origin, SchemaDefinition definition, Submission submission) {
            requireOpen();
            return store.schema().register(origin, definition, submission);
        }

        @Override
        public SchemaPlan plan(Origin origin, SchemaDefinition definition) {
            requireOpen();
            return store.schema().plan(origin, definition);
        }

        @Override
        public List<Problem> validate(Origin origin, SchemaDefinition definition) {
            requireOpen();
            return store.schema().validate(origin, definition);
        }

        @Override
        public List<RuleDescriptor> availableRules() {
            requireOpen();
            return store.schema().availableRules();
        }

        @Override
        public Optional<StoredDefinition> storedDefinition(String originName) {
            requireOpen();
            return store.schema().storedDefinition(originName);
        }

        @Override
        public Schema current() {
            requireOpen();
            return store.schema().current();
        }

        @Override
        public Schema asOf(Basis basis) {
            requireOpen();
            return store.schema().asOf(basis);
        }

        @Override
        public Schema asOf(Instant transactionTime) {
            requireOpen();
            return store.schema().asOf(transactionTime);
        }

        @Override
        public List<SchemaTransaction> history() {
            requireOpen();
            return store.schema().history();
        }
    }
}
