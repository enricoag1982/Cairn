package io.github.enricoag1982.cairn.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builds a {@link TraitDefinition} (capability {@code type-model}).
 */
public final class TraitBuilder {

    private final String name;
    private final List<AttributeDefinition> attributes = new ArrayList<>();
    private final List<String> aliases = new ArrayList<>();
    private boolean deprecated;

    TraitBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Declares an optional attribute.
     *
     * @param name      the attribute name
     * @param valueType the kind of value
     * @return this builder
     */
    public TraitBuilder attribute(String name, ValueType valueType) {
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
    public TraitBuilder attribute(String name, ValueType valueType, Consumer<AttributeBuilder> spec) {
        AttributeBuilder builder = AttributeDefinition.builder(name, valueType);
        spec.accept(builder);
        attributes.add(builder.build());
        return this;
    }

    /**
     * Adds previous names of the trait.
     *
     * @param names the previous names
     * @return this builder
     */
    public TraitBuilder aliases(String... names) {
        aliases.addAll(List.of(names));
        return this;
    }

    /**
     * Marks the trait as deprecated.
     *
     * @return this builder
     */
    public TraitBuilder deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the trait definition
     */
    public TraitDefinition build() {
        return new TraitDefinition(name, attributes, aliases, deprecated);
    }
}
