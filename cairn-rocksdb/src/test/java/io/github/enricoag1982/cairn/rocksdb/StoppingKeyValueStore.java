package io.github.enricoag1982.cairn.rocksdb;

import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.PersistentKeyValueStore;
import io.github.enricoag1982.cairn.core.storage.StoredMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A persistent key-value store for tests that stands for a process that stops while a purge compacts its files: it passes
 * everything on to a RocksDB store, and once a purge wrote its batch, which holds the note that its files are still to be
 * compacted, it fails when it is asked to flush or to compact, as the process that stops does not get to. What the purge's
 * batch wrote is on disk, as it is after a crash; what the purge did not get to is not. Flushes before the purge, such as
 * writing a snapshot, work as they do.
 */
final class StoppingKeyValueStore implements PersistentKeyValueStore {

    /** What the process does not get to. */
    enum Stop {
        /** The purge's batch was written; the process stops before it flushes. */
        BEFORE_FLUSH,
        /** The purge's batch was written and flushed; the process stops before it compacts. */
        BEFORE_COMPACTION
    }

    private final PersistentKeyValueStore delegate;
    private final Stop stop;

    StoppingKeyValueStore(PersistentKeyValueStore delegate, Stop stop) {
        this.delegate = delegate;
        this.stop = stop;
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public Stream<Entry> scan(byte[] from, byte[] to) {
        return delegate.scan(from, to);
    }

    @Override
    public void apply(Batch batch) {
        delegate.apply(batch);
    }

    @Override
    public void flush() {
        if (stop == Stop.BEFORE_FLUSH && purgeIsPending()) {
            throw stopped();
        }
        delegate.flush();
    }

    @Override
    public void compactAll() {
        if (purgeIsPending()) {
            throw stopped();
        }
        delegate.compactAll();
    }

    /** Tells whether a purge wrote its batch and did not finish compacting. */
    private boolean purgeIsPending() {
        return StoredMetadata.isCompactionPending(delegate);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static UncheckedIOException stopped() {
        return new UncheckedIOException("The process stopped.", new IOException("Stopped as a test asked."));
    }
}
