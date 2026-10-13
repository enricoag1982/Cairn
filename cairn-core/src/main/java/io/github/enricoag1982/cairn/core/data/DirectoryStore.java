package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.CommitLog;
import io.github.enricoag1982.cairn.core.storage.DirectoryLock;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.Recovery;
import io.github.enricoag1982.cairn.core.storage.Snapshots;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.IdGenerator;
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
     *                                  end of the last log segment, or a file was written in a format version that is
     *                                  not known; the message names the file and, for damage, the position
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
            store.underWriterLock(() -> {
                store.sequence().resume(recovered.latest(), recovered.lastTransactionTime());
                return null;
            });
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
    public Basis writeSnapshot() {
        requireOpen();
        return snapshot();
    }

    /** Writes a snapshot file of the latest state and deletes what the two newest snapshot files make unnecessary. */
    private Basis snapshot() {
        snapshotLock.lock();
        try {
            Snapshots.Content content = StoreImage.of(store);
            Snapshots.write(directory, content);
            Snapshots.prune(directory);
            return content.basis();
        } catch (IOException e) {
            throw new UncheckedIOException("Writing a snapshot file in " + directory + " failed.", e);
        } finally {
            snapshotLock.unlock();
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

    /** Makes the next write to the log fail, as a failing disk would. For tests of a commit that cannot be written. */
    void breakLog() {
        log.breakChannel();
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
