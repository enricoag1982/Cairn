package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The definition of one attribute of an entity type, trait or relationship type (capability {@code type-model}).
 *
 * <p>Allowed values, a pattern and lengths are for string attributes only. A pattern is a regular expression in
 * I-Regexp (RFC 9485) that a value must match as a whole; lengths count Unicode code points. Nothing of this is
 * checked when a definition is built; a {@link SchemaRegistry} validates it.
 *
 * @param name          the attribute name
 * @param valueType     the kind of value the attribute holds
 * @param required      {@code true} if every entity or relationship must have a value
 * @param unique        {@code true} if no two entities may have the same value at the same time
 * @param range         the interval that values must lie in, for ordered value types only
 * @param aliases       previous names of the attribute
 * @param deprecated    {@code true} if the attribute should no longer be used
 * @param description   what the attribute means, if described
 * @param examples      typical values; each must fit the value type and satisfy every constraint
 * @param allowedValues the values a string attribute may have; empty if any value is allowed
 * @param pattern       the I-Regexp (RFC 9485) a string value must match as a whole, if any
 * @param minLength     the smallest length of a string value, if any
 * @param maxLength     the largest length of a string value, if any
 */
public record AttributeDefinition(
        String name,
        ValueType valueType,
        boolean required,
        boolean unique,
        Optional<Range> range,
        List<String> aliases,
        boolean deprecated,
        Optional<String> description,
        List<Value> examples,
        List<String> allowedValues,
        Optional<String> pattern,
        OptionalInt minLength,
        OptionalInt maxLength) {

    /**
     * Rejects null components and copies the lists.
     */
    public AttributeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(minLength, "minLength");
        Objects.requireNonNull(maxLength, "maxLength");
        aliases = List.copyOf(aliases);
        examples = List.copyOf(examples);
        allowedValues = List.copyOf(allowedValues);
    }

    /**
     * Starts building an attribute definition.
     *
     * @param name      the attribute name
     * @param valueType the kind of value the attribute holds
     * @return a builder for an optional, non-unique attribute without a range or other constraints
     */
    public static AttributeBuilder builder(String name, ValueType valueType) {
        return new AttributeBuilder(name, valueType);
    }
}
