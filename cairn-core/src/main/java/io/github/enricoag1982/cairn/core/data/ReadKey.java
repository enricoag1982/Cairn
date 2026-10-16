package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.Set;

/**
 * What a rule reads, apart from when (design D1 of {@code add-rule-rechecks}, ADR-0025), and what a transaction
 * changes, in the same terms (D2): an entity, a relationship, the relationships that navigation finds from an entity by
 * a relationship type in one direction, or the entities of an entity type.
 *
 * <p>A key of a type covers the type's subtypes. A read names the type that its name resolves to, and a change names the
 * type of the thing that changes and each of its supertypes, so that a change meets the reads of the supertypes.
 */
sealed interface ReadKey {

    /** One entity: read by {@code entity}, by {@code timeline} and by every entity state a read returns. */
    record Entity(EntityId id) implements ReadKey {
    }

    /** One relationship: read by {@code relationship}, by {@code relationshipTimeline} and by every state returned. */
    record Relationship(RelationshipId id) implements ReadKey {
    }

    /**
     * The relationships that navigation finds from an entity: the relationship type is the one that the name resolves
     * to, and {@code forward} tells whether the name leads from source to target (the type's name) or from target to
     * source (its inverse name).
     */
    record Navigation(EntityId from, TypeId type, boolean forward) implements ReadKey {
    }

    /**
     * The entities of an entity type and its subtypes: read by {@code entities} and by the start of a query that gives
     * no list of IDs. A query that starts from given entities reads them one by one, as {@link Entity} keys, and an
     * empty list reads nothing.
     */
    record OfType(TypeId type) implements ReadKey {
    }

    /**
     * Tells whether the key names an entity or relationship by an ID in a set, as a purge removes them (ADR-0024): such
     * a key must not stay in the store.
     *
     * @param ids the values of the IDs
     * @return {@code true} if the key mentions one of them
     */
    default boolean mentions(Set<String> ids) {
        String mentioned = mentioned();
        return mentioned != null && ids.contains(mentioned);
    }

    /** The value of the ID that the key mentions; null for a key of a type, which mentions none. */
    default String mentioned() {
        return switch (this) {
            case Entity entity -> entity.id().value();
            case Relationship relationship -> relationship.id().value();
            case Navigation navigation -> navigation.from().value();
            case OfType ignored -> null;
        };
    }
}
