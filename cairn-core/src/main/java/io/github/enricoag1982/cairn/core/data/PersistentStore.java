package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.core.kv.PersistentKeyValueStore;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
import io.github.enricoag1982.cairn.core.storage.StoredMetadata;
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
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * A store over a {@linkplain PersistentKeyValueStore persistent key-value store} such as RocksDB (ADR-0028, design of
 * {@code add-rocksdb-store}): an {@link InMemoryStore} whose ledger and metadata live in the key-value store, so that
 * the store and the in-memory store share every line of model logic. Use
 * {@link io.github.enricoag1982.cairn.core.kv.KeyValueStores#openDurable} to open one.
 *
 * <p>Opening checks the format version of the key-value store, which a new store gets. If a purge wrote its batch and was
 * stopped before it finished compacting the files, which its note, a key of the metadata, says, opening finishes it first:
 * it flushes and compacts the key-value store and deletes the note, so that no file keeps what that purge removed. Then it
 * loads the metadata that earlier commits wrote (the schema registrations, the transaction records, the idempotency keys
 * with their receipts, the purge records and the digests of purged IDs) into the engine, checks that the ID generator
 * accepts the IDs the store holds, lets the sequence of bases and transaction times continue after the latest commit, and
 * runs the store's rules once to learn what they read. There is no replay of transactions: the ledger's keys hold the
 * state.
 *
 * <p>From then on every commit, schema registration and purge is one batch of the state and the metadata, which the
 * key-value store applies durably before the store records the transaction and publishes its basis. If applying a batch
 * fails, the transaction is not applied, and the store refuses commits and schema registrations until it is opened
 * again, while reads and dry runs go on.
 *
 * <p>A purge deletes the keys of what it removes and writes its record, and the note that the files are still to be
 * compacted, in one batch; if that batch cannot be written, nothing is applied. Then, still holding the writer lock, it
 * flushes and compacts the key-value store, which makes the key-value store drop what the purge deleted from its files
 * and from its own records of them, and deletes the note before it returns, so that no file keeps the purged data. If
 * flushing or compacting fails, the purge is applied, the call throws, and the store refuses commits until it is opened
 * again, which finishes the compaction while the note is there. If the key-value store cannot be reopened after the
 * compaction, as RocksDB is to rewrite its own records of its files, it is unusable, and every call but {@code close}
 * throws until the store is opened again.
 *
 * <p>{@link #writeSnapshot()} flushes the key-value store, and so does {@link #close()}, as closing a store kept in a
 * directory writes a snapshot file, so that opening the store again replays no write-ahead log; a store that refuses
 * writes after a failure does not flush when it closes. After {@code close}, every call but {@code close} throws an
 * {@code IllegalStateException}, on the store and on its schema registry; closing closes the key-value store, also if the
 * flush fails, and then the call throws.
 */
public final class PersistentStore implements DurableStore {

    private final InMemoryStore store;
    private final Keys keys;
    private final SchemaRegistry registry;
    /** Serializes flushes, purges and closing; the writer lock is not needed to flush. */
    private final ReentrantLock maintenanceLock = new ReentrantLock();
    private volatile boolean closed;

    private PersistentStore(InMemoryStore store, Keys keys) {
        this.store = store;
        this.keys = keys;
        this.registry = new OpenRegistry(store.schema(), this::requireOpen);
    }

    /**
     * Opens the store over a key-value store, which it owns from now on, also if opening fails.
     *
     * @param persistent    the key-value store, which no one else writes to
     * @param clock         the source of transaction times
     * @param generator     the ID generator of the store, which the store owns from now on
     * @param rules         the rules with code, which the store runs at commit
     * @param declaredRules the descriptions of rules that the store has no code for
     * @return the open store
     * @throws java.io.UncheckedIOException if reading or writing the key-value store fails
     * @throws IllegalStateException        if the key-value store holds keys but no format version, or a format version
     *                                      that is not known, which the message names, a stored record is damaged, or the
     *                                      generator does not accept the ID of an entity or a relationship that the store
     *                                      holds
     * @throws IllegalArgumentException     if a rule's name or version breaks the rules for rule references, or if two
     *                                      rules have the same name and version
     */
    public static PersistentStore open(PersistentKeyValueStore persistent, InstantSource clock, IdGenerator generator,
            Collection<? extends Rule> rules, Collection<RuleDescriptor> declaredRules) {
        Objects.requireNonNull(persistent, "persistent");
        boolean opened = false;
        try {
            Keys keys = new Keys(persistent);
            InMemoryStore store = new InMemoryStore(keys, clock, generator, rules, declaredRules);
            requireFormat(persistent);
            finishCompaction(persistent);
            StoredMetadata.load(persistent, new Restorer(store));
            StoredMetadata.Latest latest = StoredMetadata.latest(persistent).orElse(null);
            store.requireIdsOfTheGenerator(generator);
            store.underWriterLock(() -> {
                store.sequence().resume(latest == null ? Basis.NONE : latest.basis(),
                        Optional.ofNullable(latest).map(StoredMetadata.Latest::transactionTime));
                return null;
            });
            store.learnReads();
            PersistentStore result = new PersistentStore(store, keys);
            store.attachPersistent(result.new Guard());
            opened = true;
            return result;
        } finally {
            if (!opened) {
                persistent.close();
            }
        }
    }

    /**
     * Gives an empty key-value store its format version, and refuses one that has another or none.
     *
     * @throws IllegalStateException if the key-value store holds keys but no format version, or one that is not known
     */
    private static void requireFormat(PersistentKeyValueStore persistent) {
        OptionalInt version = StoredMetadata.formatVersion(persistent);
        if (version.isEmpty()) {
            if (!StoredMetadata.isEmpty(persistent)) {
                throw new IllegalStateException("The store holds keys but no format version, so Cairn did not write it.");
            }
            Batch created = new Batch();
            StoredMetadata.putFormatVersion(created, StoredMetadata.FORMAT_VERSION);
            persistent.apply(created);
        } else if (version.getAsInt() != StoredMetadata.FORMAT_VERSION) {
            throw new IllegalStateException("The store was written in format version " + version.getAsInt()
                    + ", which this version of Cairn does not know; it reads version " + StoredMetadata.FORMAT_VERSION
                    + ".");
        }
    }

    /**
     * Finishes what a purge that was stopped started: flushes and compacts the key-value store, and deletes the note that
     * the purge wrote. A store whose purge compacted its files but stopped before it deleted the note compacts them again.
     */
    private static void finishCompaction(PersistentKeyValueStore persistent) {
        if (StoredMetadata.isCompactionPending(persistent)) {
            persistent.flush();
            persistent.compactAll();
            Batch done = new Batch();
            StoredMetadata.deleteCompactionPending(done);
            persistent.apply(done);
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
        // A thread that holds the writer lock, such as a rule that purges, must not take the maintenance lock after it,
        // which close() takes first: refuse it before any lock is taken.
        store.refuseWriterLockHolder();
        // The maintenance lock first, as close() takes it, and the writer lock second, inside the purge.
        maintenanceLock.lock();
        try {
            requireOpen();
            return store.purge(purge, this::compact);
        } finally {
            maintenanceLock.unlock();
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
     * Makes a purge that was applied leave nothing in the files: flushes the key-value store and compacts all of it, and
     * then deletes the note that the purge wrote. The caller holds the maintenance lock and the writer lock. If it fails,
     * the store refuses further commits, because the files may still hold the purged data, and the note stays, so that
     * opening the store finishes the compaction.
     */
    private void compact() {
        try {
            keys.persistent.flush();
            keys.persistent.compactAll();
            Batch done = new Batch();
            StoredMetadata.deleteCompactionPending(done);
            keys.apply(done);
        } catch (RuntimeException | Error e) {
            keys.failed = true;
            throw e;
        }
    }

    /**
     * Writes what the key-value store holds in memory to its files, which opening the store again would otherwise read
     * from the write-ahead log. A store keeps no snapshot files.
     */
    @Override
    public Basis writeSnapshot() {
        maintenanceLock.lock();
        try {
            // Closing holds this lock while it releases the key-value store, so the store can have been closed while this
            // call waited for the lock.
            requireOpen();
            Basis latest = store.latestBasis();
            keys.persistent.flush();
            return latest;
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public void close() {
        maintenanceLock.lock();
        try {
            if (closed) {
                return;
            }
            // Taking the writer lock lets a commit that is running finish, and refuses the ones that follow.
            store.underWriterLock(() -> {
                closed = true;
                return null;
            });
            // As a store kept in a directory writes a snapshot file when it closes, this one flushes, unless a write or
            // a purge's compaction failed: the key-value store may be unusable then, and the files are for the next
            // opening to finish. The key-value store is closed in any case, and a failure to flush is reported after.
            RuntimeException failure = null;
            try {
                if (!keys.failed) {
                    keys.persistent.flush();
                }
            } catch (RuntimeException e) {
                failure = e;
            }
            try {
                keys.persistent.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    /**
     * The key-value store that the ledger reads and writes: it passes everything on, and remembers that a batch could not
     * be applied, so that the store refuses further commits.
     */
    private static final class Keys implements KeyValueStore {

        private final PersistentKeyValueStore persistent;
        /** Set when a write failed, and read under the writer lock by the commits that follow. */
        volatile boolean failed;

        Keys(PersistentKeyValueStore persistent) {
            this.persistent = persistent;
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            return persistent.get(key);
        }

        @Override
        public Stream<Entry> scan(byte[] from, byte[] to) {
            return persistent.scan(from, to);
        }

        @Override
        public void apply(Batch batch) {
            try {
                persistent.apply(batch);
            } catch (RuntimeException | Error e) {
                failed = true;
                throw e;
            }
        }
    }

    /**
     * The journal of the in-memory store: it refuses writes once the store is closed or a write failed, and writes the
     * schema registrations, which the registry hands it. The store and its registry call it holding the writer lock,
     * which {@link #close()} takes to close the store, so a commit is either finished before the store is closed or
     * refused. Data commits and purges write their metadata with their state and never reach {@link #append}.
     */
    private final class Guard implements Journal {

        @Override
        public void requireWritable() {
            requireOpen();
            if (keys.failed) {
                throw new IllegalStateException("A commit could not be written to the store, so it refuses further "
                        + "commits and schema registrations until it is opened again.");
            }
        }

        @Override
        public void append(LogRecord record) {
            requireWritable();
            if (!(record instanceof SchemaRecord schema)) {
                throw new IllegalArgumentException("Only schema registrations are written through the guard, not "
                        + record.getClass().getSimpleName() + ".");
            }
            Batch batch = new Batch();
            StoredMetadata.putLatest(batch, schema.basis(), schema.transactionTime());
            StoredMetadata.putSchema(batch, schema);
            keys.apply(batch);
        }
    }
}
