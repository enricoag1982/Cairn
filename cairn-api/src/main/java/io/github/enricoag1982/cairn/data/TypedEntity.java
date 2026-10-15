package io.github.enricoag1982.cairn.data;

/**
 * An entity that was read as a generated Java type (ADR-0021, capability {@code java-types}). Every interface that the
 * generator writes for an entity type or a trait extends it, so that code and rules can work on any entity of a
 * generated type, whatever its type.
 *
 * <p>A typed entity is a value: it holds the {@linkplain #entityState() generic state} it was read from, and
 * nothing else changes it. To change an entity, commit a transaction.
 */
public interface TypedEntity {

    /**
     * The generic state that this entity was read from: its ID and concrete type, its values, its effective period
     * and the transaction that recorded it.
     *
     * @return the state, never null
     */
    EntityState entityState();

    /**
     * The ID of the entity.
     *
     * @return the ID of the {@linkplain #entityState() state}
     */
    default EntityId id() {
        return entityState().id();
    }
}
