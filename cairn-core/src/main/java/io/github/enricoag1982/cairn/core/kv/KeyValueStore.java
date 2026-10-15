package io.github.enricoag1982.cairn.core.kv;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * An ordered key-value store of byte strings, compared as unsigned bytes (ADR-0011, ADR-0027): the interface that the
 * engine's model logic stores everything on, so that an in-memory store and a store on disk behave alike. It knows
 * nothing of what the keys and values mean.
 *
 * <p>One thread at a time applies batches; any number of threads read, without locks. A reader that starts after a batch
 * was applied sees all of it. A reader that is running while a batch is applied may see part of it, which the engine's
 * keys make harmless: every value says the bases it belongs to.
 *
 * <p>Arrays that a store returns belong to it, and callers must not change them. A store may keep the arrays of a
 * {@link Batch} it applies.
 */
public interface KeyValueStore {

    /**
     * Reads one key.
     *
     * @param key the key
     * @return the value, or empty if the store has no such key
     */
    Optional<byte[]> get(byte[] key);

    /**
     * Reads the entries of a range in the order of their keys, as they are while the stream is read: an entry that a
     * batch adds or removes meanwhile may or may not be in it.
     *
     * @param from the first key of the range
     * @param to   the first key after the range; {@code null} for a range without an end
     * @return the entries, in unsigned byte order of their keys
     */
    Stream<Entry> scan(byte[] from, byte[] to);

    /**
     * Reads the entries of a range.
     *
     * @param range the range
     * @return the entries, in unsigned byte order of their keys
     * @see #scan(byte[], byte[])
     */
    default Stream<Entry> scan(KeyRange range) {
        return scan(range.from(), range.to());
    }

    /**
     * Applies the operations of a batch in order, as one atomic change for readers that start afterwards.
     *
     * @param batch the batch
     */
    void apply(Batch batch);

    /**
     * A key with its value.
     *
     * @param key   the key, which callers must not change
     * @param value the value, which callers must not change
     */
    record Entry(byte[] key, byte[] value) {
    }
}
