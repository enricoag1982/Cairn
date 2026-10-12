package io.github.enricoag1982.sysrec.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds an {@link AttributeDefinition}. Attributes start optional, not unique and without a range.
 */
public final class AttributeBuilder {

    private final String name;
    private final ValueType valueType;
    private boolean required;
    private boolean unique;
    private Range range;
    private final List<String> aliases = new ArrayList<>();
    private boolean deprecated;

    AttributeBuilder(String name, ValueType valueType) {
        this.name = Objects.requireNonNull(name, "name");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    /**
     * Makes the attribute required.
     *
     * @return this builder
     */
    public AttributeBuilder required() {
        this.required = true;
        return this;
    }

    /**
     * Makes the attribute unique.
     *
     * @return this builder
     */
    public AttributeBuilder unique() {
        this.unique = true;
        return this;
    }

    /**
     * Restricts the attribute to a range of values.
     *
     * @param range the allowed values
     * @return this builder
     */
    public AttributeBuilder range(Range range) {
        this.range = Objects.requireNonNull(range, "range");
        return this;
    }

    /**
     * Adds previous names of the attribute.
     *
     * @param names the previous names
     * @return this builder
     */
    public AttributeBuilder aliases(String... names) {
        aliases.addAll(List.of(names));
        return this;
    }

    /**
     * Marks the attribute as deprecated.
     *
     * @return this builder
     */
    public AttributeBuilder deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the attribute definition
     */
    public AttributeDefinition build() {
        return new AttributeDefinition(name, valueType, required, unique, Optional.ofNullable(range), aliases, deprecated);
    }
}
