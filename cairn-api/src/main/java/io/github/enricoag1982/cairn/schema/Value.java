package io.github.enricoag1982.cairn.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A typed value used as a range bound or as an example of an attribute's values (capability {@code type-model}).
 * Strings and booleans are never valid range bounds, because their attributes cannot have a range; they exist for examples.
 */
public sealed interface Value {

    /**
     * The value type this value belongs to.
     *
     * @return the value type
     */
    ValueType valueType();

    /**
     * Creates an int value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(int value) {
        return new IntValue(value);
    }

    /**
     * Creates a long value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(long value) {
        return new LongValue(value);
    }

    /**
     * Creates a decimal value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(BigDecimal value) {
        return new DecimalValue(value);
    }

    /**
     * Creates a string value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(String value) {
        return new StringValue(value);
    }

    /**
     * Creates a boolean value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(boolean value) {
        return new BooleanValue(value);
    }

    /**
     * Creates a date value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(LocalDate value) {
        return new DateValue(value);
    }

    /**
     * Creates an instant value.
     *
     * @param value the value
     * @return the value
     */
    static Value of(Instant value) {
        return new InstantValue(value);
    }

    /**
     * A string constant.
     *
     * @param value the value
     */
    record StringValue(String value) implements Value {
        /**
         * Rejects a null value.
         */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType valueType() {
            return ValueType.STRING;
        }
    }

    /**
     * A boolean constant.
     *
     * @param value the value
     */
    record BooleanValue(boolean value) implements Value {
        @Override
        public ValueType valueType() {
            return ValueType.BOOLEAN;
        }
    }

    /**
     * An int constant.
     *
     * @param value the value
     */
    record IntValue(int value) implements Value {
        @Override
        public ValueType valueType() {
            return ValueType.INT;
        }
    }

    /**
     * A long constant.
     *
     * @param value the value
     */
    record LongValue(long value) implements Value {
        @Override
        public ValueType valueType() {
            return ValueType.LONG;
        }
    }

    /**
     * A decimal constant.
     *
     * @param value the value
     */
    record DecimalValue(BigDecimal value) implements Value {
        /**
         * Rejects a null value.
         */
        public DecimalValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType valueType() {
            return ValueType.DECIMAL;
        }
    }

    /**
     * A date constant.
     *
     * @param value the value
     */
    record DateValue(LocalDate value) implements Value {
        /**
         * Rejects a null value.
         */
        public DateValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType valueType() {
            return ValueType.DATE;
        }
    }

    /**
     * An instant constant.
     *
     * @param value the value
     */
    record InstantValue(Instant value) implements Value {
        /**
         * Rejects a null value.
         */
        public InstantValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType valueType() {
            return ValueType.INSTANT;
        }
    }
}
