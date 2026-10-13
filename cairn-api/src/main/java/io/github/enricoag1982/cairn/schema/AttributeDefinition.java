package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The definition of one attribute of an entity type, trait or relationship type (capability {@code type-model}).
 *
 * @param name       the attribute name
 * @param valueType  the kind of value the attribute holds
 * @param required   {@code true} if every entity or relationship must have a value
 * @param unique     {@code true} if no two entities may have the same value at the same time
 * @param range      the allowed values, for ordered value types only
 * @param aliases    previous names of the attribute
 * @param deprecated {@code true} if the attribute should no longer be used
 */
public record AttributeDefinition(
        String name,
        ValueType valueType,
        boolean required,
        boolean unique,
        Optional<Range> range,
        List<String> aliases,
        boolean deprecated) {

    /**
     * Rejects null components and copies the alias list.
     */
    public AttributeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(range, "range");
        aliases = List.copyOf(aliases);
    }

    /**
     * Starts building an attribute definition.
     *
     * @param name      the attribute name
     * @param valueType the kind of value the attribute holds
     * @return a builder for an optional, non-unique attribute without a range
     */
    public static AttributeBuilder builder(String name, ValueType valueType) {
        return new AttributeBuilder(name, valueType);
    }
}
