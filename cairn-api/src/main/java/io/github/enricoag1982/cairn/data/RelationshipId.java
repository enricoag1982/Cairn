package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * The ID of a relationship (capability {@code relationships}). It comes from the same generator as the IDs of entities,
 * the store's {@link IdGenerator}, or is supplied by the caller when the relationship is created. No entity and no
 * relationship share an ID: the IDs of a store are unique across both.
 *
 * <p>A value is not checked against the format of a generator when the ID is built, because the format belongs to
 * the store: a store rejects a supplied ID that its generator does not {@linkplain IdGenerator#accepts accept} with
 * the problem {@code INVALID_ENTITY_ID}. IDs are ordered by their text, which for the IDs of the generators of
 * the engine is also the order in which they were generated.
 *
 * @param value the canonical text form
 */
public record RelationshipId(String value) implements Id, Comparable<RelationshipId> {

    /**
     * Rejects a null value.
     */
    public RelationshipId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public int compareTo(RelationshipId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
