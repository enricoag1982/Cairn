package io.github.enricoag1982.cairn.data;

/**
 * A relationship that was read as a generated Java type (ADR-0021, capability {@code java-types}). Every interface that
 * the generator writes for a relationship type extends it.
 *
 * <p>A typed relationship is a value: it holds the {@linkplain #relationshipState() generic state} it was read from,
 * and nothing else changes it. To change a relationship, commit a transaction.
 */
public interface TypedRelationship {

    /**
     * The generic state that this relationship was read from: its ID and type, its ends, its values, its effective
     * period and the transaction that recorded it.
     *
     * @return the state, never null
     */
    RelationshipState relationshipState();

    /**
     * The ID of the relationship.
     *
     * @return the ID of the {@linkplain #relationshipState() state}
     */
    default RelationshipId id() {
        return relationshipState().id();
    }

    /**
     * The ID of the entity at the source end.
     *
     * @return the source of the {@linkplain #relationshipState() state}
     */
    default EntityId source() {
        return relationshipState().source();
    }

    /**
     * The ID of the entity at the target end.
     *
     * @return the target of the {@linkplain #relationshipState() state}
     */
    default EntityId target() {
        return relationshipState().target();
    }
}
