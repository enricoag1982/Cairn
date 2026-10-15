package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * What a {@linkplain UniqueKey unique key} that a write names resolved to when the transaction was committed (ADR-0026,
 * capability {@code transactions}): the position of the write, the key as the write names it, and the entity that has
 * its value at the start of the write's effective period. The {@linkplain Receipt receipt} lists one for each key that a
 * write names, so that a caller learns which entity a key named: the same key can name different entities in writes with
 * different periods.
 *
 * @param write  the position of the write in the transaction, counted from 0
 * @param key    the key, as the write names it
 * @param entity the ID of the entity that the key resolved to
 */
public record KeyResolution(int write, UniqueKey key, EntityId entity) {

    /**
     * Rejects a negative position and null components.
     *
     * @throws IllegalArgumentException if the position is negative
     * @throws NullPointerException     if the key or the entity is null
     */
    public KeyResolution {
        if (write < 0) {
            throw new IllegalArgumentException("The position of a write is 0 or more, but it is " + write + ".");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
    }
}
