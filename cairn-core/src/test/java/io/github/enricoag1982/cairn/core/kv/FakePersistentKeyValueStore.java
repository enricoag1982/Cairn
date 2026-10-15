package io.github.enricoag1982.cairn.core.kv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * A persistent key-value store for tests, which stands for a database on disk without one: the keys are in an
 * {@link InMemoryKeyValueStore} that outlives the store, so that {@link #reopened()} gives a store that holds what the
 * closed one held, as opening a database again does. It remembers the batches that were applied and the calls that were
 * made, in order, and fails as a failing disk would when it is told to.
 */
public final class FakePersistentKeyValueStore implements PersistentKeyValueStore {

    private final InMemoryKeyValueStore data;
    private final List<Batch> batches = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile boolean failApplies;
    private volatile boolean failCompactions;
    private volatile boolean failFlushes;
    /** How many more batches are applied before every apply fails; negative for none. */
    private volatile int appliesBeforeFailure = -1;

    /**
     * Creates an empty store.
     */
    public FakePersistentKeyValueStore() {
        this(new InMemoryKeyValueStore());
    }

    private FakePersistentKeyValueStore(InMemoryKeyValueStore data) {
        this.data = data;
    }

    /**
     * Opens the same keys again, as a new store that has made no calls yet.
     *
     * @return the store
     */
    public FakePersistentKeyValueStore reopened() {
        return new FakePersistentKeyValueStore(data);
    }

    /**
     * The keys, which the store shares with the stores that {@link #reopened()} gives, to read and to change as a disk
     * that someone else wrote to.
     *
     * @return the keys
     */
    public InMemoryKeyValueStore data() {
        return data;
    }

    /** Makes every {@link #apply} fail from now on, without changing the keys. */
    public void failApplies() {
        failApplies = true;
    }

    /**
     * Lets the given number of batches be applied, and makes every {@link #apply} after them fail, without changing the
     * keys.
     *
     * @param applies the number of batches that still succeed
     */
    public void failAppliesAfter(int applies) {
        appliesBeforeFailure = applies;
    }

    /** Makes every {@link #flush} fail from now on. */
    public void failFlushes() {
        failFlushes = true;
    }

    /** Makes every {@link #compactAll} fail from now on. */
    public void failCompactions() {
        failCompactions = true;
    }

    /**
     * The batches that were applied, oldest first; a batch that failed is not one of them.
     *
     * @return the batches
     */
    public List<Batch> batches() {
        return batches;
    }

    /**
     * The calls that were made, oldest first: {@code apply}, {@code flush}, {@code compact} and {@code close}.
     *
     * @return the names
     */
    public List<String> events() {
        return events;
    }

    /**
     * Tells whether the store was closed.
     *
     * @return {@code true} after {@link #close()}
     */
    public boolean isClosed() {
        return closed;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The store is closed.");
        }
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        requireOpen();
        return data.get(key);
    }

    @Override
    public Stream<Entry> scan(byte[] from, byte[] to) {
        requireOpen();
        return data.scan(from, to);
    }

    @Override
    public void apply(Batch batch) {
        requireOpen();
        if (appliesBeforeFailure == 0) {
            failApplies = true;
        } else if (appliesBeforeFailure > 0) {
            appliesBeforeFailure--;
        }
        if (failApplies) {
            throw new UncheckedIOException("The disk is full.", new IOException("No space left on device"));
        }
        events.add("apply");
        batches.add(batch);
        data.apply(batch);
    }

    @Override
    public void flush() {
        requireOpen();
        events.add("flush");
        if (failFlushes) {
            throw new UncheckedIOException("The flush failed.", new IOException("Input/output error"));
        }
    }

    @Override
    public void compactAll() {
        requireOpen();
        events.add("compact");
        if (failCompactions) {
            throw new UncheckedIOException("The compaction failed.", new IOException("Input/output error"));
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            events.add("close");
        }
    }
}
