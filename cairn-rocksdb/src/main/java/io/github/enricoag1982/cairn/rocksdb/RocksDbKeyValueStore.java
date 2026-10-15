package io.github.enricoag1982.cairn.rocksdb;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.PersistentKeyValueStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * A {@link PersistentKeyValueStore} on a RocksDB database (ADR-0028, design D5 of {@code add-rocksdb-store}): {@code get}
 * and {@code scan} through RocksDB's iterators, which order keys as unsigned bytes with the default comparator, {@code
 * apply} as a {@link WriteBatch} written with {@code sync}, so that RocksDB's write-ahead log is the commit log, {@code
 * flush} as a flush that waits, and {@code compactAll} as a compaction of the whole key range with the bottommost level
 * forced, so that no data file keeps a deleted value, followed by closing the database and opening it again, which makes
 * RocksDB write a new MANIFEST and delete the old one and the old info log. The MANIFEST holds the smallest and the
 * largest key of every file, so a key that a purge deleted, an ID or the value of a unique attribute, stays in it until
 * then; a compaction alone would leave it there. The database is opened with the write-ahead log not archived: a log that
 * a flush made obsolete is deleted.
 *
 * <p>The database is native memory, and a call on a closed database can crash the virtual machine, so every call, and
 * every step of a scan, takes the read lock of a gate, which {@link #close()} and the reopening take exclusively: they wait
 * for the calls that are running, and a call after {@code close} fails with an {@code IllegalStateException}. The
 * compaction itself runs under the read lock, so readers go on while it runs; only the reopening makes them wait.
 *
 * <p>A scan survives the reopening: it remembers the last key it returned, closes its RocksDB iterator before the
 * database is closed, and takes a new one on the reopened database, which it positions after that key. A scan that is
 * running while a purge compacts therefore reads the keys that are left, as a reader of the in-memory store that runs while
 * a batch is applied reads part of it. If the database cannot be reopened, the store is unusable: {@code compactAll}
 * throws, every later call but {@code close} throws an {@code IllegalStateException}, and {@code close} still releases
 * the directory.
 */
final class RocksDbKeyValueStore implements PersistentKeyValueStore {

    /** The database, or null if reopening it failed; changes only while the gate's write lock is held. */
    private RocksDB db;
    private final Path path;
    private final Options options;
    private final WriteOptions writeOptions;
    private final FlushOptions flushOptions;
    private final CompactRangeOptions compactOptions;
    /** Releases the lock of the store's directory when the database is closed. */
    private final Runnable releaseDirectory;
    /** Calls take the read lock, closing the write lock. */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    /** The scans that are not finished, which closing the database finishes; guarded by the gate. */
    private final Set<Cursor> cursors = ConcurrentHashMap.newKeySet();
    /** Guarded by the gate. */
    private boolean closed;

    private RocksDbKeyValueStore(RocksDB db, Path path, Options options, WriteOptions writeOptions,
            FlushOptions flushOptions, CompactRangeOptions compactOptions, Runnable releaseDirectory) {
        this.db = db;
        this.path = path;
        this.options = options;
        this.writeOptions = writeOptions;
        this.flushOptions = flushOptions;
        this.compactOptions = compactOptions;
        this.releaseDirectory = releaseDirectory;
    }

    /**
     * Opens the database in a directory, which is created if it does not exist.
     *
     * @param path             the directory of the database
     * @param releaseDirectory releases the lock of the store's directory; the store runs it when it is closed, and
     *                         the caller runs it if this method fails
     * @return the open store
     * @throws UncheckedIOException if RocksDB cannot open the database, for example because another process holds its lock
     */
    static RocksDbKeyValueStore open(Path path, Runnable releaseDirectory) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(releaseDirectory, "releaseDirectory");
        RocksDB.loadLibrary();
        Options options = new Options().setCreateIfMissing(true).setWalTtlSeconds(0).setWalSizeLimitMB(0);
        WriteOptions writeOptions = new WriteOptions().setSync(true);
        FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true);
        CompactRangeOptions compactOptions = new CompactRangeOptions()
                .setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kForce);
        try {
            RocksDB db = RocksDB.open(options, path.toString());
            return new RocksDbKeyValueStore(db, path, options, writeOptions, flushOptions, compactOptions,
                    releaseDirectory);
        } catch (RocksDBException e) {
            compactOptions.close();
            flushOptions.close();
            writeOptions.close();
            options.close();
            throw failed("Opening the RocksDB database in " + path, e);
        }
    }

    private static UncheckedIOException failed(String what, RocksDBException e) {
        return new UncheckedIOException(what + " failed.", new IOException(e.getMessage(), e));
    }

    /** Called holding the gate. */
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The store is closed.");
        }
        if (db == null) {
            throw new IllegalStateException("The database could not be opened again after a compaction, so the store "
                    + "is unusable; close it and open it again.");
        }
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Objects.requireNonNull(key, "key");
        gate.readLock().lock();
        try {
            requireOpen();
            return Optional.ofNullable(db.get(key));
        } catch (RocksDBException e) {
            throw failed("Reading a key from RocksDB", e);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public Stream<Entry> scan(byte[] from, byte[] to) {
        Objects.requireNonNull(from, "from");
        gate.readLock().lock();
        try {
            requireOpen();
            Cursor cursor = new Cursor(db.newIterator(), from, to);
            cursors.add(cursor);
            return StreamSupport.stream(cursor, false).onClose(cursor::close);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public void apply(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return;
        }
        gate.readLock().lock();
        try {
            requireOpen();
            try (WriteBatch writes = new WriteBatch()) {
                for (Batch.Operation operation : batch.operations()) {
                    switch (operation) {
                        case Batch.Put put -> writes.put(put.key(), put.value());
                        case Batch.Delete delete -> writes.delete(delete.key());
                    }
                }
                db.write(writeOptions, writes);
            }
        } catch (RocksDBException e) {
            throw failed("Writing a batch to RocksDB", e);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public void flush() {
        gate.readLock().lock();
        try {
            requireOpen();
            db.flush(flushOptions);
        } catch (RocksDBException e) {
            throw failed("Flushing RocksDB", e);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public void compactAll() {
        gate.readLock().lock();
        try {
            requireOpen();
            db.compactRange(db.getDefaultColumnFamily(), null, null, compactOptions);
        } catch (RocksDBException e) {
            throw failed("Compacting RocksDB", e);
        } finally {
            gate.readLock().unlock();
        }
        gate.writeLock().lock();
        try {
            requireOpen();
            reopen();
        } finally {
            gate.writeLock().unlock();
        }
    }

    /**
     * Closes the database and opens it again with the same options, so that RocksDB writes a new MANIFEST, which names
     * only the files that are left, and deletes the old one and the old info log. The scans keep going: each gives up its
     * iterator before the database closes and takes one on the new database. The directory stays locked. Called holding
     * the write lock of the gate. If it fails, the store is unusable.
     *
     * @throws UncheckedIOException if closing or opening the database fails
     */
    private void reopen() {
        RocksDB old = db;
        db = null;
        try {
            for (Cursor cursor : cursors) {
                cursor.detach();
            }
            old.closeE();
            db = RocksDB.open(options, path.toString());
            for (Cursor cursor : cursors) {
                cursor.attach();
            }
        } catch (RocksDBException e) {
            becomeUnusable();
            throw failed("Opening the RocksDB database in " + path + " again", e);
        }
    }

    /** Makes the store unusable after a failed reopening: closes what is open, and drops the database. */
    private void becomeUnusable() {
        for (Cursor cursor : cursors) {
            cursor.finish();
        }
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Override
    public void close() {
        gate.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            RocksDBException failure = null;
            try {
                for (Cursor cursor : cursors) {
                    cursor.finish();
                }
                if (db != null) {
                    db.closeE();
                }
            } catch (RocksDBException e) {
                failure = e;
            } finally {
                try {
                    compactOptions.close();
                    flushOptions.close();
                    writeOptions.close();
                    options.close();
                } finally {
                    releaseDirectory.run();
                }
            }
            if (failure != null) {
                throw failed("Closing RocksDB", failure);
            }
        } finally {
            gate.writeLock().unlock();
        }
    }

    /**
     * A scan: a RocksDB iterator, positioned at the first key of the range, which a stream reads. Each step takes the read
     * lock of the gate, so closing the database waits for it. The scan remembers the last key it returned, so that it can
     * go on on a database that was opened again.
     */
    private final class Cursor extends Spliterators.AbstractSpliterator<Entry> {

        private final byte[] from;
        private final byte[] to;
        /** The iterator, or null while the database is closed for its reopening or after the scan is finished. */
        private RocksIterator iterator;
        /** The last key that a step returned; null before the first. */
        private byte[] last;
        /** Guarded by the gate. */
        private boolean finished;

        Cursor(RocksIterator iterator, byte[] from, byte[] to) {
            super(Long.MAX_VALUE, ORDERED | NONNULL);
            this.iterator = iterator;
            this.from = from;
            this.to = to;
            iterator.seek(from);
        }

        @Override
        public boolean tryAdvance(Consumer<? super Entry> action) {
            Entry entry;
            gate.readLock().lock();
            try {
                requireOpen();
                if (finished) {
                    return false;
                }
                if (!iterator.isValid()) {
                    iterator.status();
                    finish();
                    return false;
                }
                byte[] key = iterator.key();
                if (to != null && Arrays.compareUnsigned(key, to) >= 0) {
                    finish();
                    return false;
                }
                entry = new Entry(key, iterator.value());
                last = key;
                iterator.next();
            } catch (RocksDBException e) {
                finish();
                throw failed("Reading a range from RocksDB", e);
            } finally {
                gate.readLock().unlock();
            }
            action.accept(entry);
            return true;
        }

        /** Gives up the iterator before the database is closed to be opened again; called holding the write lock. */
        void detach() {
            if (iterator != null) {
                iterator.close();
                iterator = null;
            }
        }

        /**
         * Takes an iterator on the database that was opened again, and positions it after the last key that a step
         * returned, or at the start of the range if none did; called holding the write lock.
         */
        void attach() throws RocksDBException {
            if (finished) {
                return;
            }
            iterator = db.newIterator();
            iterator.seek(last == null ? from : last);
            if (last != null && iterator.isValid() && Arrays.equals(iterator.key(), last)) {
                iterator.next();
            }
            iterator.status();
        }

        /** Releases the iterator, once; called holding the gate. */
        void finish() {
            if (!finished) {
                finished = true;
                cursors.remove(this);
                detach();
            }
        }

        /** Closes the stream: releases the iterator, unless closing the database did. */
        void close() {
            gate.readLock().lock();
            try {
                finish();
            } finally {
                gate.readLock().unlock();
            }
        }
    }
}
