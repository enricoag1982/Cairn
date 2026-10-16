package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An attribute in a registered schema (capability {@code type-model}).
 *
 * @param id            the schema identifier
 * @param name          the name
 * @param aliases       previous names
 * @param valueType     the kind of value
 * @param required      {@code true} if a value is required
 * @param unique        {@code true} if values must be unique
 * @param range         the interval that values must lie in, if restricted; for ordered value types only
 * @param deprecated    {@code true} if the attribute should no longer be used
 * @param description   what the attribute means, if described
 * @param examples      typical values
 * @param allowedValues the values a string attribute may have; empty if any value is allowed
 * @param pattern       the I-Regexp (RFC 9485) a string value must match as a whole, if any
 * @param minLength     the smallest length of a string value in Unicode code points, if any
 * @param maxLength     the largest length of a string value in Unicode code points, if any
 * @param derived       the expression that computes the attribute's value at commit, if the attribute is derived
 *                      (capability {@code derived-attributes}, ADR-0031); a write cannot set a derived attribute
 * @param declaredBy    the name of the entity type, trait or relationship type that declares the attribute
 * @param classification the classification of the attribute, such as {@code pay}, if it has one (capability
 *                      {@code attribute-access}, ADR-0036); a view restricted to a clearance that does not cover it
 *                      withholds the attribute's values, and a transaction with such a clearance cannot write them
 */
public record Attribute(
        AttributeId id,
        String name,
        List<String> aliases,
        ValueType valueType,
        boolean required,
        boolean unique,
        Optional<Range> range,
        boolean deprecated,
        Optional<String> description,
        List<Value> examples,
        List<String> allowedValues,
        Optional<String> pattern,
        OptionalInt minLength,
        OptionalInt maxLength,
        Optional<String> derived,
        String declaredBy,
        Optional<String> classification) {

    /**
     * Copies the lists and rejects a null classification.
     */
    public Attribute {
        aliases = List.copyOf(aliases);
        examples = List.copyOf(examples);
        allowedValues = List.copyOf(allowedValues);
        Objects.requireNonNull(classification, "classification");
    }

    /**
     * Creates an attribute that has no classification.
     *
     * @param id            the schema identifier
     * @param name          the name
     * @param aliases       previous names
     * @param valueType     the kind of value
     * @param required      {@code true} if a value is required
     * @param unique        {@code true} if values must be unique
     * @param range         the interval that values must lie in, if restricted
     * @param deprecated    {@code true} if the attribute should no longer be used
     * @param description   what the attribute means, if described
     * @param examples      typical values
     * @param allowedValues the values a string attribute may have; empty if any value is allowed
     * @param pattern       the I-Regexp (RFC 9485) a string value must match as a whole, if any
     * @param minLength     the smallest length of a string value in Unicode code points, if any
     * @param maxLength     the largest length of a string value in Unicode code points, if any
     * @param derived       the expression that computes the attribute's value at commit, if the attribute is derived
     * @param declaredBy    the name of the entity type, trait or relationship type that declares the attribute
     */
    public Attribute(
            AttributeId id,
            String name,
            List<String> aliases,
            ValueType valueType,
            boolean required,
            boolean unique,
            Optional<Range> range,
            boolean deprecated,
            Optional<String> description,
            List<Value> examples,
            List<String> allowedValues,
            Optional<String> pattern,
            OptionalInt minLength,
            OptionalInt maxLength,
            Optional<String> derived,
            String declaredBy) {
        this(id, name, aliases, valueType, required, unique, range, deprecated, description, examples, allowedValues,
                pattern, minLength, maxLength, derived, declaredBy, Optional.empty());
    }

    /**
     * Tells whether this attribute has the given name or alias, compared without regard to case.
     *
     * @param nameOrAlias the name to look for
     * @return {@code true} if the name or one of the aliases matches
     */
    public boolean isNamed(String nameOrAlias) {
        return name.equalsIgnoreCase(nameOrAlias) || aliases.stream().anyMatch(nameOrAlias::equalsIgnoreCase);
    }
}
