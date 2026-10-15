package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.CommitLog;
import io.github.enricoag1982.cairn.core.storage.DirectoryLock;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.PurgeFiles;
import io.github.enricoag1982.cairn.core.storage.PurgeLogRecord;
import io.github.enricoag1982.cairn.core.storage.Recovery;
import io.github.enricoag1982.cairn.core.storage.Snapshots;
import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
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
 * <p>Recovery only applies what was committed. The store's rules are run once afterwards, over every version their types
 * name them for, only to learn what they read, so that a transaction can run them again when it changes that (ADR-0025,
 * design D5 of {@code add-rule-rechecks}); what they report, throw or try to write is dropped, and the files do not
 * change. Only a virtual machine that runs out of memory or fails stops the store from opening.
 *
 * <p>After {@code close}, every call but {@code close} throws an {@code IllegalStateException}, on the store and on its
 * schema registry. After a failed write, commits and registrations throw one, while reads and dry runs go on.
 *
 * <p>The index of the change feed (ADR-0029, design D6 of {@code add-change-feed}) is kept in memory with the rest of the
 * ledger, and recovery writes it again as it writes the rest. Closing the store completes the subscriptions to its feed.
 */
public final class DirectoryStore implements DurableStore {

    /**
     * The subdirectory in which a RocksDB store keeps its database (module {@code cairn-rocksdb}, ADR-0028). A directory
     * that has it was written by that store, and this store refuses it. The name is repeated here, and in
     * {@code RocksDbStores}, because this module does not depend on the other.
     */
    static final String ROCKSDB_SUBDIRECTORY = "rocksdb";

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
        this.registry = new OpenRegistry(store.schema(), this::requireOpen);
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
     * @throws IllegalStateException    if another open store holds the directory, the directory holds a RocksDB store
     *                                  (a subdirectory {@code rocksdb}), a file is damaged anywhere but at the end of the
     *                                  last log segment, a file was written in a format version that is not known, or the
     *                                  generator does not accept the ID of an entity or a relationship that the store
     *                                  holds; the message names the file and, for damage, the position, or the ID
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two rules
     *                                  have the same name and version
     */
    public static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules) {
        return open(directory, clock, generator, rules, declaredRules, Optional.empty());
    }

    /**
     * Opens the store in a directory, as {@link #open(Path, InstantSource, IdGenerator, Collection, Collection)} does,
     * with an expression language that compiles and runs the checks of the store's schema. The checks are compiled again
     * when the schema is recovered. A store without an expression language keeps its data readable, but rejects writes to
     * the types that checks apply to.
     *
     * @param directory     the directory
     * @param clock         the source of transaction times
     * @param generator     the ID generator of the store, which the store owns from now on
     * @param rules         the rules with code, which the store runs at commit
     * @param declaredRules the descriptions of rules that the store has no code for
     * @param language      the expression language, if there is one
     * @return the open store
     * @throws UncheckedIOException     if reading or writing the directory fails
     * @throws IllegalStateException    as for the method without a language
     * @throws IllegalArgumentException as for the method without a language
     */
    public static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules,
            Optional<ExpressionLanguage> language) {
        return open(directory, clock, generator, rules, declaredRules, language, CommitLog.DEFAULT_SEGMENT_LIMIT);
    }

    /** Opens a store whose log segments are complete after {@code segmentLimit} bytes; for tests of segments. */
    static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules, long segmentLimit) {
        return open(directory, clock, generator, rules, declaredRules, Optional.empty(), segmentLimit);
    }

    private static DirectoryStore open(Path directory, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules,
            Optional<ExpressionLanguage> language, long segmentLimit) {
        Objects.requireNonNull(directory, "directory");
        InMemoryStore store = new InMemoryStore(clock, generator, rules, declaredRules, language);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Creating the directory " + directory + " failed.", e);
        }
        DirectoryLock lock = DirectoryLock.acquire(directory);
        CommitLog log = null;
        boolean opened = false;
        try {
            requireNoRocksDbStore(directory);
            Recovery.Result recovered = Recovery.recover(directory, new Restorer(store));
            store.requireIdsOfTheGenerator(generator);
            store.underWriterLock(() -> {
                store.sequence().resume(recovered.latest(), recovered.lastTransactionTime());
                return null;
            });
            completePurge(directory, store, recovered);
            store.learnReads();
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
     * Refuses a directory that a RocksDB store wrote, before anything is read or created in it: the store would open as an
     * empty store, with a log and snapshot files of its own beside the database, and the next opening of the directory as
     * a RocksDB store would not see what it wrote. Called with the directory's lock held, so that a directory that is in
     * use is reported as such.
     *
     * @throws IllegalStateException naming the directory and the kind of store that wrote it
     */
    private static void requireNoRocksDbStore(Path directory) {
        if (Files.isDirectory(directory.resolve(ROCKSDB_SUBDIRECTORY))) {
            throw new IllegalStateException("The directory " + directory + " holds a RocksDB store (subdirectory "
                    + ROCKSDB_SUBDIRECTORY + "); open it with RocksDbStores.open in the module cairn-rocksdb, not as a "
                    + "store kept through a commit log and snapshot files.");
        }
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
    public List<Change> changes(Basis after, int limit) {
        requireOpen();
        return store.changes(after, limit);
    }

    @Override
    public Flow.Publisher<Change> changeFeed(Basis after) {
        requireOpen();
        return store.changeFeed(after);
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
            // The subscriptions to the change feed complete, and the reads they have running finish.
            store.closeFeed();
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
            store.closeFeed();
            release();
        } finally {
            snapshotLock.unlock();
        }
    }

    /** The entities and relationships of the store, as keys and values; for tests that look at them. */
    Ledger ledger() {
        return store.ledger();
    }

    /** What the store's rules read when they last checked, which is learned after recovery; for tests. */
    ReadIndex readIndex() {
        return store.readIndex();
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
}
