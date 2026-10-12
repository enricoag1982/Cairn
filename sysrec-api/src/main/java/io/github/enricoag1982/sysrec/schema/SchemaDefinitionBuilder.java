package io.github.enricoag1982.sysrec.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builds a {@link SchemaDefinition}, for example:
 *
 * <pre>{@code
 * SchemaDefinition.builder()
 *     .trait("Named", t -> t.attribute("name", ValueType.STRING, a -> a.required()))
 *     .entityType("Party", e -> e.abstractType().includes("Named"))
 *     .entityType("Organization", e -> e.supertype("Party").attribute("lei", ValueType.STRING, a -> a.unique()))
 *     .relationshipType("Owns", r -> r.from("Organization").to("Party").inverse("ownedBy"))
 *     .build();
 * }</pre>
 */
public final class SchemaDefinitionBuilder {

    private final List<EntityTypeDefinition> entityTypes = new ArrayList<>();
    private final List<TraitDefinition> traits = new ArrayList<>();
    private final List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();

    SchemaDefinitionBuilder() {
    }

    /**
     * Adds a concrete entity type without supertype, traits or attributes.
     *
     * @param name the type name
     * @return this builder
     */
    public SchemaDefinitionBuilder entityType(String name) {
        return entityType(name, type -> { });
    }

    /**
     * Adds an entity type.
     *
     * @param name the type name
     * @param spec sets the type's properties
     * @return this builder
     */
    public SchemaDefinitionBuilder entityType(String name, Consumer<EntityTypeBuilder> spec) {
        EntityTypeBuilder builder = new EntityTypeBuilder(name);
        spec.accept(builder);
        entityTypes.add(builder.build());
        return this;
    }

    /**
     * Adds a trait.
     *
     * @param name the trait name
     * @param spec sets the trait's properties
     * @return this builder
     */
    public SchemaDefinitionBuilder trait(String name, Consumer<TraitBuilder> spec) {
        TraitBuilder builder = new TraitBuilder(name);
        spec.accept(builder);
        traits.add(builder.build());
        return this;
    }

    /**
     * Adds a relationship type.
     *
     * @param name the type name
     * @param spec sets the type's properties
     * @return this builder
     */
    public SchemaDefinitionBuilder relationshipType(String name, Consumer<RelationshipTypeBuilder> spec) {
        RelationshipTypeBuilder builder = new RelationshipTypeBuilder(name);
        spec.accept(builder);
        relationshipTypes.add(builder.build());
        return this;
    }

    /**
     * Adds an entity type that is already defined.
     *
     * @param type the entity type
     * @return this builder
     */
    public SchemaDefinitionBuilder add(EntityTypeDefinition type) {
        entityTypes.add(Objects.requireNonNull(type, "type"));
        return this;
    }

    /**
     * Adds a trait that is already defined.
     *
     * @param trait the trait
     * @return this builder
     */
    public SchemaDefinitionBuilder add(TraitDefinition trait) {
        traits.add(Objects.requireNonNull(trait, "trait"));
        return this;
    }

    /**
     * Adds a relationship type that is already defined.
     *
     * @param type the relationship type
     * @return this builder
     */
    public SchemaDefinitionBuilder add(RelationshipTypeDefinition type) {
        relationshipTypes.add(Objects.requireNonNull(type, "type"));
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the schema definition
     */
    public SchemaDefinition build() {
        return new SchemaDefinition(entityTypes, traits, relationshipTypes);
    }
}
