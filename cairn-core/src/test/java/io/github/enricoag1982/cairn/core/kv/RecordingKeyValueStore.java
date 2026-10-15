package io.github.enricoag1982.cairn.core.kv;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * A key-value store for tests that remembers every batch it was asked to apply, in order, and holds what the batches
 * wrote in an {@link InMemoryKeyValueStore}.
 */
public final class RecordingKeyValueStore implements KeyValueStore {

    private final KeyValueStore delegate = new InMemoryKeyValueStore();
    private final List<Batch> batches = new CopyOnWriteArrayList<>();

    /**
     * Creates an empty store.
     */
    public RecordingKeyValueStore() {
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
        batches.add(batch);
        delegate.apply(batch);
    }

    /**
     * The batches that were applied, oldest first.
     *
     * @return the batches
     */
    public List<Batch> batches() {
        return batches;
    }

    /**
     * Every entry that the store holds.
     *
     * @return the entries, in the order of their keys
     */
    public List<Entry> entries() {
        try (Stream<Entry> all = scan(KeyRange.all())) {
            return all.toList();
        }
    }
}
