package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something a {@link Condition} compares (ADR-0023, capability {@code queries}): an attribute of a binding, the ID or the
 * type of a binding, or a literal. IDs and type names are strings.
 */
public sealed interface Operand {

    /**
     * An attribute of a bound entity or relationship. It has no value if the attribute has none, or if the binding is
     * empty.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute, compared without regard to case
     */
    record AttributeOf(String binding, String attribute) implements Operand {

        /**
         * Rejects null components.
         */
        public AttributeOf {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(attribute, "attribute");
        }
    }

    /**
     * The ID of a bound entity or relationship, as a string. It has no value if the binding is empty.
     *
     * @param binding the name of the binding
     */
    record IdOf(String binding) implements Operand {

        /**
         * Rejects a null binding.
         */
        public IdOf {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * The exact type of a bound entity or relationship, as the name of its concrete type in the view's schema, a string.
     * It has no value if the binding is empty. Use {@link Condition.IsA} to include subtypes.
     *
     * @param binding the name of the binding
     */
    record TypeOf(String binding) implements Operand {

        /**
         * Rejects a null binding.
         */
        public TypeOf {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * A constant. When it is compared with an attribute, it takes the attribute's value type where it can: a number
     * compares by value whatever its value type, and a string that spells a date or an instant in ISO 8601 form is
     * read as one for a date or instant attribute.
     *
     * @param value the value
     */
    record Literal(Value value) implements Operand {

        /**
         * Rejects a null value.
         */
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * An attribute of a binding.
     *
     * @param binding   the name of the binding
     * @param attribute the name or an alias of the attribute
     * @return the operand
     */
    static AttributeOf attribute(String binding, String attribute) {
        return new AttributeOf(binding, attribute);
    }

    /**
     * The ID of a binding.
     *
     * @param binding the name of the binding
     * @return the operand
     */
    static IdOf id(String binding) {
        return new IdOf(binding);
    }

    /**
     * The exact type of a binding.
     *
     * @param binding the name of the binding
     * @return the operand
     */
    static TypeOf type(String binding) {
        return new TypeOf(binding);
    }

    /**
     * A constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(Value value) {
        return new Literal(value);
    }

    /**
     * A string constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(String value) {
        return new Literal(Value.of(value));
    }

    /**
     * A boolean constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(boolean value) {
        return new Literal(Value.of(value));
    }

    /**
     * An int constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(int value) {
        return new Literal(Value.of(value));
    }

    /**
     * A long constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(long value) {
        return new Literal(Value.of(value));
    }

    /**
     * A decimal constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(BigDecimal value) {
        return new Literal(Value.of(value));
    }

    /**
     * A date constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(LocalDate value) {
        return new Literal(Value.of(value));
    }

    /**
     * An instant constant.
     *
     * @param value the value
     * @return the operand
     */
    static Literal literal(Instant value) {
        return new Literal(Value.of(value));
    }
}
