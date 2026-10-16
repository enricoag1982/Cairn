package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The definition of one attribute of an entity type, trait or relationship type (capability {@code type-model}).
 *
 * <p>Allowed values, a pattern and lengths are for string attributes only. A pattern is a regular expression in
 * I-Regexp (RFC 9485) that a value must match as a whole; lengths count Unicode code points. A derived attribute states
 * an expression over {@code self}, the attributes of the version that are not derived, instead of taking values from
 * writes, and keeps every other property: its constraints apply to the computed value. The expression must not be blank
 * and has at most {@value Check#MAXIMUM_EXPRESSION_LENGTH} characters, as the expression of a check does. Nothing of this
 * is checked when a definition is built; a {@link SchemaRegistry} validates it.
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
 * @param derived       the expression in the Common Expression Language that computes the attribute's value from the
 *                      other values of the same version at commit, if the attribute is derived (capability
 *                      {@code derived-attributes}, ADR-0031); a derived attribute takes no values from writes
 * @param classification the classification of the attribute, such as {@code pay}, if it has one (capability
 *                      {@code attribute-access}, ADR-0036): a name of lowercase letters, digits and hyphens that starts
 *                      with a letter, which a clearance must cover to read or write the attribute's values; a derived
 *                      attribute that reads classified attributes must have the classification that they have
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
        OptionalInt maxLength,
        Optional<String> derived,
        Optional<String> classification) {

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
        Objects.requireNonNull(derived, "derived");
        Objects.requireNonNull(classification, "classification");
        aliases = List.copyOf(aliases);
        examples = List.copyOf(examples);
        allowedValues = List.copyOf(allowedValues);
    }

    /**
     * Creates an attribute definition that has no classification.
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
     * @param derived       the expression that computes the attribute's value at commit, if the attribute is derived
     */
    public AttributeDefinition(
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
            OptionalInt maxLength,
            Optional<String> derived) {
        this(name, valueType, required, unique, range, aliases, deprecated, description, examples, allowedValues,
                pattern, minLength, maxLength, derived, Optional.empty());
    }

    /**
     * Creates an attribute definition that is not derived and has no classification.
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
    public AttributeDefinition(
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
        this(name, valueType, required, unique, range, aliases, deprecated, description, examples, allowedValues,
                pattern, minLength, maxLength, Optional.empty(), Optional.empty());
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
