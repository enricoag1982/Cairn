package io.github.enricoag1982.cairn.data;

import java.util.Objects;

/**
 * One entity that a traversal reached (ADR-0010, capability {@code relationships}): the entity, how many relationships
 * away from the start it is, and the relationship the traversal followed to reach it.
 *
 * @param entity the state of the entity that was reached, as the {@link View} that traversed reads it
 * @param depth  the number of relationships followed from the start, at least 1: the entities one relationship away
 *               from the start have depth 1
 * @param via    the state of the relationship that the traversal followed to reach the entity from the entity at the
 *               depth before
 */
public record Step(EntityState entity, int depth, RelationshipState via) {

    /**
     * Rejects null components and a depth below 1.
     */
    public Step {
        Objects.requireNonNull(entity, "entity");
        if (depth < 1) {
            throw new IllegalArgumentException("The depth of a step is at least 1, but it is " + depth + ".");
        }
        Objects.requireNonNull(via, "via");
    }
}
