package io.github.enricoag1982.cairn.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Builds an {@link AttributeDefinition} (capability {@code type-model}). Attributes start optional, not unique, not
 * derived and without a range, description, examples or string constraints.
 */
public final class AttributeBuilder {

    private final String name;
    private final ValueType valueType;
    private boolean required;
    private boolean unique;
    private Range range;
    private final List<String> aliases = new ArrayList<>();
    private boolean deprecated;
    private String description;
    private final List<Value> examples = new ArrayList<>();
    private final List<String> allowedValues = new ArrayList<>();
    private String pattern;
    private OptionalInt minLength = OptionalInt.empty();
    private OptionalInt maxLength = OptionalInt.empty();
    private String derived;

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
     * Restricts the attribute to an interval of values.
     *
     * @param range the interval that values must lie in
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
     * Describes the attribute.
     *
     * @param text what the attribute means
     * @return this builder
     */
    public AttributeBuilder description(String text) {
        this.description = Objects.requireNonNull(text, "text");
        return this;
    }

    /**
     * Adds examples of the attribute's values.
     *
     * @param values the examples
     * @return this builder
     */
    public AttributeBuilder examples(Value... values) {
        examples.addAll(List.of(values));
        return this;
    }

    /**
     * Adds allowed values; a string attribute may then have only these values.
     *
     * @param values the allowed values
     * @return this builder
     */
    public AttributeBuilder allowedValues(String... values) {
        allowedValues.addAll(List.of(values));
        return this;
    }

    /**
     * Requires string values to match a pattern as a whole.
     *
     * @param regularExpression the pattern, in I-Regexp (RFC 9485)
     * @return this builder
     */
    public AttributeBuilder pattern(String regularExpression) {
        this.pattern = Objects.requireNonNull(regularExpression, "regularExpression");
        return this;
    }

    /**
     * Sets the smallest length of string values, counted in Unicode code points.
     *
     * @param length the minimum length
     * @return this builder
     */
    public AttributeBuilder minLength(int length) {
        this.minLength = OptionalInt.of(length);
        return this;
    }

    /**
     * Sets the largest length of string values, counted in Unicode code points.
     *
     * @param length the maximum length
     * @return this builder
     */
    public AttributeBuilder maxLength(int length) {
        this.maxLength = OptionalInt.of(length);
        return this;
    }

    /**
     * Makes the attribute derived (capability {@code derived-attributes}): its value is computed at commit from the other
     * values of the same version, by an expression in the Common Expression Language that reads the attributes that are
     * not derived as {@code self.<name>}. A write cannot set a derived attribute.
     *
     * @param expression the expression, for example {@code self.givenName + ' ' + self.familyName}
     * @return this builder
     */
    public AttributeBuilder derived(String expression) {
        this.derived = Objects.requireNonNull(expression, "expression");
        return this;
    }

    /**
     * Builds the definition.
     *
     * @return the attribute definition
     */
    public AttributeDefinition build() {
        return new AttributeDefinition(name, valueType, required, unique, Optional.ofNullable(range), aliases, deprecated,
                Optional.ofNullable(description), examples, allowedValues, Optional.ofNullable(pattern),
                minLength, maxLength, Optional.ofNullable(derived));
    }
}
