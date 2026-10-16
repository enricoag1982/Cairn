package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.schema.AttributeBuilder;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Small helpers that derive changed schema definitions in tests.
 */
public final class Definitions {

    private Definitions() {
    }

    /**
     * Replaces one entity type.
     *
     * @param definition the definition to change
     * @param name       the name of the entity type to replace
     * @param change     computes the new entity type from the old one
     * @return the changed definition
     */
    public static SchemaDefinition changeEntityType(
            SchemaDefinition definition, String name, UnaryOperator<EntityTypeDefinition> change) {
        return new SchemaDefinition(
                replace(definition.entityTypes(), name, EntityTypeDefinition::name, change),
                definition.traits(),
                definition.relationshipTypes());
    }

    /**
     * Replaces one trait.
     *
     * @param definition the definition to change
     * @param name       the name of the trait to replace
     * @param change     computes the new trait from the old one
     * @return the changed definition
     */
    public static SchemaDefinition changeTrait(
            SchemaDefinition definition, String name, UnaryOperator<TraitDefinition> change) {
        return new SchemaDefinition(
                definition.entityTypes(),
                replace(definition.traits(), name, TraitDefinition::name, change),
                definition.relationshipTypes());
    }

    /**
     * Replaces one relationship type.
     *
     * @param definition the definition to change
     * @param name       the name of the relationship type to replace
     * @param change     computes the new relationship type from the old one
     * @return the changed definition
     */
    public static SchemaDefinition changeRelationshipType(
            SchemaDefinition definition, String name, UnaryOperator<RelationshipTypeDefinition> change) {
        return new SchemaDefinition(
                definition.entityTypes(),
                definition.traits(),
                replace(definition.relationshipTypes(), name, RelationshipTypeDefinition::name, change));
    }

    /**
     * Makes a relationship type name a rule, as its description says: the rule's name and version are added to the rules
     * the type names.
     *
     * @param definition       the definition to change
     * @param relationshipType the name of the relationship type that names the rule
     * @param rule             the description of the rule to name
     * @return the changed definition
     */
    public static SchemaDefinition withRule(
            SchemaDefinition definition, String relationshipType, RuleDescriptor rule) {
        return changeRelationshipType(definition, relationshipType, type -> {
            List<RuleReference> rules = new ArrayList<>(type.rules());
            rules.add(new RuleReference(rule.name(), rule.version()));
            return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                    type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                    type.outlivesTarget(), type.supertype(), type.attributes(), type.aliases(), type.deprecated(),
                    type.description(), rules, type.checks());
        });
    }

    /**
     * Makes an entity type or relationship type declare a check: the check is added to the checks the type declares.
     *
     * @param definition the definition to change
     * @param type       the name of the entity type or relationship type that declares the check
     * @param name       the check's name
     * @param expression the check's expression
     * @param message    the check's message, or {@code null} for none
     * @return the changed definition
     */
    public static SchemaDefinition withCheck(
            SchemaDefinition definition, String type, String name, String expression, String message) {
        Check check = new Check(name, expression, Optional.ofNullable(message));
        if (definition.entityTypes().stream().anyMatch(candidate -> candidate.name().equals(type))) {
            return changeEntityType(definition, type, entity -> {
                List<Check> checks = new ArrayList<>(entity.checks());
                checks.add(check);
                return new EntityTypeDefinition(entity.name(), entity.isAbstract(), entity.supertype(), entity.traits(),
                        entity.attributes(), entity.aliases(), entity.deprecated(), entity.description(),
                        entity.rules(), checks);
            });
        }
        return changeRelationshipType(definition, type, relationship -> {
            List<Check> checks = new ArrayList<>(relationship.checks());
            checks.add(check);
            return new RelationshipTypeDefinition(relationship.name(), relationship.source(), relationship.target(),
                    relationship.targetsPerSource(), relationship.sourcesPerTarget(), relationship.inverseName(),
                    relationship.inverseAliases(), relationship.kind(), relationship.symmetric(),
                    relationship.outlivesTarget(), relationship.supertype(), relationship.attributes(),
                    relationship.aliases(), relationship.deprecated(), relationship.description(),
                    relationship.rules(), checks);
        });
    }

    /**
     * Adds a derived attribute to an entity type or relationship type: the attribute is added to the attributes the type
     * declares.
     *
     * @param definition the definition to change
     * @param type       the name of the entity type or relationship type that declares the attribute
     * @param name       the attribute's name
     * @param valueType  the attribute's value type
     * @param required   whether the attribute is required
     * @param expression the attribute's expression
     * @return the changed definition
     */
    public static SchemaDefinition withDerivedAttribute(SchemaDefinition definition, String type, String name,
            ValueType valueType, boolean required, String expression) {
        AttributeBuilder builder = AttributeDefinition.builder(name, valueType).derived(expression);
        if (required) {
            builder.required();
        }
        AttributeDefinition attribute = builder.build();
        if (definition.entityTypes().stream().anyMatch(candidate -> candidate.name().equals(type))) {
            return changeEntityType(definition, type, entity -> {
                List<AttributeDefinition> attributes = new ArrayList<>(entity.attributes());
                attributes.add(attribute);
                return withAttributes(entity, attributes);
            });
        }
        return changeRelationshipType(definition, type, relationship -> {
            List<AttributeDefinition> attributes = new ArrayList<>(relationship.attributes());
            attributes.add(attribute);
            return new RelationshipTypeDefinition(relationship.name(), relationship.source(), relationship.target(),
                    relationship.targetsPerSource(), relationship.sourcesPerTarget(), relationship.inverseName(),
                    relationship.inverseAliases(), relationship.kind(), relationship.symmetric(),
                    relationship.outlivesTarget(), relationship.supertype(), attributes, relationship.aliases(),
                    relationship.deprecated(), relationship.description(), relationship.rules(),
                    relationship.checks());
        });
    }

    /**
     * Classifies an attribute of an entity type, relationship type or trait, or removes its classification: the
     * test-only classifications of the scenarios of attribute access, such as {@code finance} for Account's
     * {@code balance}.
     *
     * @param definition     the definition to change
     * @param type           the name of the entity type, relationship type or trait that declares the attribute
     * @param attribute      the attribute's name
     * @param classification the classification, or {@code null} for none
     * @return the changed definition
     */
    public static SchemaDefinition withClassification(
            SchemaDefinition definition, String type, String attribute, String classification) {
        UnaryOperator<AttributeDefinition> classify = old -> new AttributeDefinition(old.name(), old.valueType(),
                old.required(), old.unique(), old.range(), old.aliases(), old.deprecated(), old.description(),
                old.examples(), old.allowedValues(), old.pattern(), old.minLength(), old.maxLength(), old.derived(),
                Optional.ofNullable(classification));
        if (definition.entityTypes().stream().anyMatch(candidate -> candidate.name().equals(type))) {
            return changeEntityType(definition, type, entity -> changeAttribute(entity, attribute, classify));
        }
        if (definition.traits().stream().anyMatch(candidate -> candidate.name().equals(type))) {
            return changeTrait(definition, type, trait -> new TraitDefinition(trait.name(),
                    replace(trait.attributes(), attribute, AttributeDefinition::name, classify), trait.aliases(),
                    trait.deprecated(), trait.description()));
        }
        return changeRelationshipType(definition, type, relationship -> new RelationshipTypeDefinition(
                relationship.name(), relationship.source(), relationship.target(), relationship.targetsPerSource(),
                relationship.sourcesPerTarget(), relationship.inverseName(), relationship.inverseAliases(),
                relationship.kind(), relationship.symmetric(), relationship.outlivesTarget(), relationship.supertype(),
                replace(relationship.attributes(), attribute, AttributeDefinition::name, classify),
                relationship.aliases(), relationship.deprecated(), relationship.description(), relationship.rules(),
                relationship.checks()));
    }

    /**
     * Removes an entity type.
     *
     * @param definition the definition to change
     * @param name       the name of the entity type to remove
     * @return the changed definition
     */
    public static SchemaDefinition withoutEntityType(SchemaDefinition definition, String name) {
        return new SchemaDefinition(
                definition.entityTypes().stream().filter(type -> !type.name().equals(name)).toList(),
                definition.traits(),
                definition.relationshipTypes());
    }

    /**
     * Makes the relationships of a relationship type outlive their targets, or not.
     *
     * @param definition       the definition to change
     * @param relationshipType the name of the relationship type
     * @param outlivesTarget   whether its relationships may continue after their target ends
     * @return the changed definition
     */
    public static SchemaDefinition withOutlivesTarget(
            SchemaDefinition definition, String relationshipType, boolean outlivesTarget) {
        return changeRelationshipType(definition, relationshipType, type -> new RelationshipTypeDefinition(type.name(),
                type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(), type.inverseName(),
                type.inverseAliases(), type.kind(), type.symmetric(), outlivesTarget, type.supertype(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules(), type.checks()));
    }

    /**
     * The test-only association {@code OpenedBy} of the scenarios of relationships that outlive their targets, to add
     * to the reference model with {@link #plus}: from an account to the person who opened it, with from 0 to 1 targets
     * per source, from 0 to unbounded sources per target, and the inverse name {@code opened}.
     *
     * @param outlivesTarget whether its relationships may continue after their target ends
     * @return a definition with the relationship type and nothing else
     */
    public static SchemaDefinition openedBy(boolean outlivesTarget) {
        return SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> {
                    r.from("Account").to("Person").targetsPerSource(Multiplicity.ZERO_OR_ONE)
                            .sourcesPerTarget(Multiplicity.ZERO_OR_MORE).inverse("opened");
                    if (outlivesTarget) {
                        r.outlivesTarget();
                    }
                })
                .build();
    }

    /**
     * Adds types from another definition.
     *
     * @param definition the definition to extend
     * @param extra      the types to add
     * @return the combined definition
     */
    public static SchemaDefinition plus(SchemaDefinition definition, SchemaDefinition extra) {
        return new SchemaDefinition(
                concat(definition.entityTypes(), extra.entityTypes()),
                concat(definition.traits(), extra.traits()),
                concat(definition.relationshipTypes(), extra.relationshipTypes()));
    }

    /**
     * Copies an entity type with other attributes.
     *
     * @param type       the entity type
     * @param attributes the new attributes
     * @return the copy
     */
    public static EntityTypeDefinition withAttributes(EntityTypeDefinition type, List<AttributeDefinition> attributes) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(), attributes,
                type.aliases(), type.deprecated(), type.description(), type.rules(), type.checks());
    }

    /**
     * Copies an entity type with one attribute changed.
     *
     * @param type   the entity type
     * @param name   the attribute to change
     * @param change computes the new attribute from the old one
     * @return the copy
     */
    public static EntityTypeDefinition changeAttribute(
            EntityTypeDefinition type, String name, UnaryOperator<AttributeDefinition> change) {
        return withAttributes(type, replace(type.attributes(), name, AttributeDefinition::name, change));
    }

    /**
     * Copies an attribute with a new name and aliases.
     *
     * @param attribute the attribute
     * @param name      the new name
     * @param aliases   the new aliases
     * @return the copy
     */
    public static AttributeDefinition renamed(AttributeDefinition attribute, String name, String... aliases) {
        return new AttributeDefinition(name, attribute.valueType(), attribute.required(), attribute.unique(),
                attribute.range(), List.of(aliases), attribute.deprecated(), attribute.description(),
                attribute.examples(), attribute.allowedValues(), attribute.pattern(), attribute.minLength(),
                attribute.maxLength(), attribute.derived(), attribute.classification());
    }

    private static <T> List<T> replace(
            List<T> items, String name, java.util.function.Function<T, String> nameOf, UnaryOperator<T> change) {
        List<T> result = new ArrayList<>();
        boolean found = false;
        for (T item : items) {
            if (nameOf.apply(item).equals(name)) {
                result.add(change.apply(item));
                found = true;
            } else {
                result.add(item);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("no element named " + name);
        }
        return result;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }
}
