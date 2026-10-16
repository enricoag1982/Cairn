package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.data.EntityId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a query starts: the entities of an entity type that are effective at the view's effective time, each bound to a
 * name, or, if the query gives a list of IDs, only the entities with those IDs that are of the type and effective
 * (ADR-0023, capability {@code queries}).
 *
 * <p>With a list of IDs, the start binds each entity once, whatever the order or the repetitions of the IDs. An ID of an
 * entity of another type, an unknown ID and an ID of an entity that is not effective at the view's effective time bind
 * nothing, and are not a problem, as a filter would leave them out. An empty list is a list: it binds nothing, so that
 * IDs computed from data that finds none give no rows and not every entity of the type. When a rule's view runs the
 * query, the start reads the given entities, not every entity of the type (ADR-0025), so a change to another entity of
 * the type does not check the rule again.
 *
 * @param entityType the name or an alias of the entity type, compared without regard to case
 * @param binding    the name that each entity is bound to in the rows
 * @param exactType  {@code true} to take only the entities whose concrete type is the entity type, {@code false} to
 *                   include the entities of its subtypes; an abstract type has no entities of its own
 * @param ids        the IDs of the entities to start from; empty to start from every entity of the type, a list, even an
 *                   empty one, to start from those entities only
 */
public record Start(String entityType, String binding, boolean exactType, Optional<List<EntityId>> ids) {

    /**
     * Rejects null components and copies the IDs.
     */
    public Start {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(ids, "ids");
        ids = ids.map(List::copyOf);
    }

    /**
     * Creates a start from every entity of the type.
     *
     * @param entityType the name or an alias of the entity type, compared without regard to case
     * @param binding    the name that each entity is bound to in the rows
     * @param exactType  {@code true} to take only the entities whose concrete type is the entity type
     */
    public Start(String entityType, String binding, boolean exactType) {
        this(entityType, binding, exactType, Optional.empty());
    }
}
