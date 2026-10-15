package io.github.enricoag1982.cairn.core.kv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of puts and deletes that a {@link KeyValueStore} applies atomically, in the order in which they were
 * added. A batch copies the keys and values it is given, so that the caller can go on using its arrays. The arrays that
 * its operations hold belong to the batch, and the store that applies it may keep them: nobody may change them.
 */
public final class Batch {

    /** One change of a batch. */
    public sealed interface Operation permits Put, Delete {

        /**
         * The key that the operation changes.
         *
         * @return the key, which callers must not change
         */
        byte[] key();
    }

    /**
     * Sets the value of a key.
     *
     * @param key   the key
     * @param value the value
     */
    public record Put(byte[] key, byte[] value) implements Operation {
    }

    /**
     * Removes a key, if the store has it.
     *
     * @param key the key
     */
    public record Delete(byte[] key) implements Operation {
    }

    private final List<Operation> operations = new ArrayList<>();

    /**
     * Creates an empty batch.
     */
    public Batch() {
    }

    /**
     * Adds a put.
     *
     * @param key   the key
     * @param value the value
     * @return this batch
     */
    public Batch put(byte[] key, byte[] value) {
        operations.add(new Put(Objects.requireNonNull(key, "key").clone(), Objects.requireNonNull(value, "value").clone()));
        return this;
    }

    /**
     * Adds a delete.
     *
     * @param key the key
     * @return this batch
     */
    public Batch delete(byte[] key) {
        operations.add(new Delete(Objects.requireNonNull(key, "key").clone()));
        return this;
    }

    /**
     * The operations, in the order in which they were added.
     *
     * @return an unmodifiable view of the operations
     */
    public List<Operation> operations() {
        return Collections.unmodifiableList(operations);
    }

    /**
     * Tells whether the batch changes nothing.
     *
     * @return {@code true} if no operation was added
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * The number of operations.
     *
     * @return the number
     */
    public int size() {
        return operations.size();
    }
}
