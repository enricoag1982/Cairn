package io.github.enricoag1982.cairn.query;

import java.util.Objects;

/**
 * Where a query starts: the entities of an entity type that are effective at the view's effective time, each bound to a
 * name (ADR-0023, capability {@code queries}).
 *
 * @param entityType the name or an alias of the entity type, compared without regard to case
 * @param binding    the name that each entity is bound to in the rows
 * @param exactType  {@code true} to take only the entities whose concrete type is the entity type, {@code false} to
 *                   include the entities of its subtypes; an abstract type has no entities of its own
 */
public record Start(String entityType, String binding, boolean exactType) {

    /**
     * Rejects null components.
     */
    public Start {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(binding, "binding");
    }
}
