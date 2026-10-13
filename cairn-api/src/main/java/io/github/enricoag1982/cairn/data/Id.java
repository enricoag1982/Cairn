package io.github.enricoag1982.cairn.data;

/**
 * The ID of something a store holds. It never changes, and callers treat it as opaque: they compare and store it, but
 * never read meaning from it (ADR-0019, capability {@code transactions}).
 *
 * <p>Entities and relationships have IDs of their own kinds, {@link EntityId} and {@link RelationshipId}. A store draws
 * both from one generator, so no entity and no relationship of a store have the same ID.
 */
public sealed interface Id extends Ref permits EntityId, RelationshipId {

    /**
     * The canonical text form of the ID, in the format of the store's {@link IdGenerator}.
     *
     * @return the text, never null
     */
    String value();
}
