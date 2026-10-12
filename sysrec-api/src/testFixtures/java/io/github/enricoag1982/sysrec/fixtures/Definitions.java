package io.github.enricoag1982.sysrec.fixtures;

import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.TraitDefinition;
import java.util.ArrayList;
import java.util.List;
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
                type.aliases(), type.deprecated());
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
                attribute.range(), List.of(aliases), attribute.deprecated());
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
