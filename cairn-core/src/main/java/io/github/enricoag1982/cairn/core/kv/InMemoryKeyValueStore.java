package io.github.enricoag1982.cairn.core.kv;

import java.util.Arrays;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

/**
 * A {@link KeyValueStore} in memory, on a skip list that orders keys as unsigned bytes: the reference implementation of
 * the interface (ADR-0011). Readers take no lock. The operations of a batch are applied one after the other, so a reader
 * that runs meanwhile sees part of it; a reader that starts afterwards sees all of it.
 */
public final class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentSkipListMap<byte[], byte[]> entries = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);

    /**
     * Creates an empty store.
     */
    public InMemoryKeyValueStore() {
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public Stream<Entry> scan(byte[] from, byte[] to) {
        Objects.requireNonNull(from, "from");
        NavigableMap<byte[], byte[]> range = to == null ? entries.tailMap(from, true)
                : Arrays.compareUnsigned(from, to) >= 0 ? entries.subMap(from, true, from, false)
                : entries.subMap(from, true, to, false);
        return range.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue()));
    }

    @Override
    public void apply(Batch batch) {
        for (Batch.Operation operation : Objects.requireNonNull(batch, "batch").operations()) {
            switch (operation) {
                case Batch.Put put -> entries.put(put.key(), put.value());
                case Batch.Delete delete -> entries.remove(delete.key());
            }
        }
    }
}
