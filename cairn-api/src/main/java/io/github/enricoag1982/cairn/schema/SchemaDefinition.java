package io.github.enricoag1982.cairn.schema;

import java.util.List;

/**
 * A schema written as plain data, before it is validated and registered (capability {@code type-model}).
 *
 * <p>Definitions refer to each other by name. Nothing is checked when a definition is built, apart from missing
 * values; a {@link SchemaRegistry} validates it and reports every problem at once.
 *
 * @param entityTypes       the entity types
 * @param traits            the traits
 * @param relationshipTypes the relationship types
 */
public record SchemaDefinition(
        List<EntityTypeDefinition> entityTypes,
        List<TraitDefinition> traits,
        List<RelationshipTypeDefinition> relationshipTypes) {

    /** A definition without any types. */
    public static final SchemaDefinition EMPTY = new SchemaDefinition(List.of(), List.of(), List.of());

    /**
     * Copies the lists.
     */
    public SchemaDefinition {
        entityTypes = List.copyOf(entityTypes);
        traits = List.copyOf(traits);
        relationshipTypes = List.copyOf(relationshipTypes);
    }

    /**
     * Starts building a schema definition.
     *
     * @return an empty builder
     */
    public static SchemaDefinitionBuilder builder() {
        return new SchemaDefinitionBuilder();
    }
}
