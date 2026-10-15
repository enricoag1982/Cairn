package io.github.enricoag1982.cairn.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds an {@link EntityTypeDefinition} (capability {@code type-model}). Entity types start concrete, without a supertype or traits.
 */
public final class EntityTypeBuilder {

    private final String name;
    private boolean isAbstract;
    private String supertype;
    private final List<String> traits = new ArrayList<>();
    private final List<AttributeDefinition> attributes = new ArrayList<>();
    private final List<String> aliases = new ArrayList<>();
    private boolean deprecated;
    private String description;
    private final List<RuleReference> rules = new ArrayList<>();

    EntityTypeBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Makes the type abstract: it has no entities of its own.
     *
     * @return this builder
     */
    public EntityTypeBuilder abstractType() {
        this.isAbstract = true;
        return this;
    }

    /**
     * Sets the entity supertype.
     *
     * @param name the name of the supertype
     * @return this builder
     */
    public EntityTypeBuilder supertype(String name) {
        this.supertype = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Includes traits.
     *
     * @param names the names of the traits
     * @return this builder
     */
    public EntityTypeBuilder includes(String... names) {
        traits.addAll(List.of(names));
        return this;
    }

    /**
     * Declares an optional attribute.
     *
     * @param name      the attribute name
     * @param valueType the kind of value
     * @return this builder
     */
    public EntityTypeBuilder attribute(String name, ValueType valueType) {
        return attribute(name, valueType, attribute -> { });
    }

    /**
     * Declares an attribute.
     *
     * @param name      the attribute name
     * @param valueType the kind of value
     * @param spec      sets the attribute's other properties
     * @return this builder
     */
    public EntityTypeBuilder attribute(String name, ValueType valueType, Consumer<AttributeBuilder> spec) {
        AttributeBuilder builder = AttributeDefinition.builder(name, valueType);
        spec.accept(builder);
        attributes.add(builder.build());
        return this;
    }

    /**
     * Declares an attribute that is already defined.
     *
     * @param attribute the attribute
     * @return this builder
     */
    public EntityTypeBuilder attribute(AttributeDefinition attribute) {
        attributes.add(Objects.requireNonNull(attribute, "attribute"));
        return this;
    }

    /**
     * Adds previous names of the type.
     *
     * @param names the previous names
     * @return this builder
     */
    public EntityTypeBuilder aliases(String... names) {
        aliases.addAll(List.of(names));
        return this;
    }

    /**
     * Marks the type as deprecated.
     *
     * @return this builder
     */
    public EntityTypeBuilder deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Describes the type.
     *
     * @param text what the type means
     * @return this builder
     */
    public EntityTypeBuilder description(String text) {
        this.description = Objects.requireNonNull(text, "text");
        return this;
    }

    /**
     * Names a rule that applies to the type and its subtypes.
     *
     * @param name    the rule's name
     * @param version the rule's version, at least 1
     * @return this builder
     */
    public EntityTypeBuilder rule(String name, int version) {
        rules.add(new RuleReference(name, version));
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the entity type definition
     */
    public EntityTypeDefinition build() {
        return new EntityTypeDefinition(
                name, isAbstract, Optional.ofNullable(supertype), traits, attributes, aliases, deprecated,
                Optional.ofNullable(description), rules);
    }
}
