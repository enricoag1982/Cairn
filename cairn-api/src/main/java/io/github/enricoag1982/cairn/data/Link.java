package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * One result of navigating a relationship from an entity (ADR-0010, capability {@code relationships}): the
 * relationship, and the entity at its other end. Both are as the {@link View} that navigated reads them, at its
 * effective time and basis.
 *
 * @param relationship the state of the relationship that was navigated; its source and target tell which end the
 *                     navigation started from
 * @param entity       the state of the entity at the other end of the relationship from the one navigated from
 */
public record Link(RelationshipState relationship, EntityState entity) {

    /**
     * Rejects null components.
     */
    public Link {
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(entity, "entity");
    }
}
