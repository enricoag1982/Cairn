package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;
import java.util.Optional;

/**
 * What the writes of a transaction need to know about the type of the thing they write, whether it is an entity or a
 * relationship (design D5 of {@code add-relationships}): its identifier, name, attributes and rules. The two kinds of
 * type are records of the API without a common interface, so this holds what they share.
 *
 * @param id             the type's identifier
 * @param name           the type's name
 * @param relationship   {@code true} for a relationship type, {@code false} for an entity type
 * @param attributes     all attributes, including inherited ones
 * @param effectiveRules the rules that apply to the type
 */
record SubjectType(TypeId id, String name, boolean relationship, List<Attribute> attributes,
        List<EffectiveRule> effectiveRules) {

    static SubjectType of(EntityType type) {
        return new SubjectType(type.id(), type.name(), false, type.attributes(), type.effectiveRules());
    }

    static SubjectType of(RelationshipType type) {
        return new SubjectType(type.id(), type.name(), true, type.attributes(), type.effectiveRules());
    }

    /** Finds an attribute by name or alias, compared without regard to case. */
    Optional<Attribute> attribute(String nameOrAlias) {
        return attributes.stream().filter(attribute -> attribute.isNamed(nameOrAlias)).findFirst();
    }

    /** Whether things of this type are called an {@code entity} or a {@code relationship}. */
    String kind() {
        return relationship ? "relationship" : "entity";
    }
}
