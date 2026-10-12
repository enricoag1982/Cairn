package io.github.enricoag1982.sysrec.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A typed constant used as a range bound (capability {@code type-model}).
 */
public sealed interface Literal {

    /**
     * The value type this literal belongs to.
     *
     * @return the value type
     */
    ValueType valueType();

    /**
     * Creates an int literal.
     *
     * @param value the value
     * @return the literal
     */
    static Literal of(int value) {
        return new IntLiteral(value);
    }

    /**
     * Creates a long literal.
     *
     * @param value the value
     * @return the literal
     */
    static Literal of(long value) {
        return new LongLiteral(value);
    }

    /**
     * Creates a decimal literal.
     *
     * @param value the value
     * @return the literal
     */
    static Literal of(BigDecimal value) {
        return new DecimalLiteral(value);
    }

    /**
     * Creates a date literal.
     *
     * @param value the value
     * @return the literal
     */
    static Literal of(LocalDate value) {
        return new DateLiteral(value);
    }

    /**
     * Creates an instant literal.
     *
     * @param value the value
     * @return the literal
     */
    static Literal of(Instant value) {
        return new InstantLiteral(value);
    }

    /**
     * An int constant.
     *
     * @param value the value
     */
    record IntLiteral(int value) implements Literal {
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
    record LongLiteral(long value) implements Literal {
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
    record DecimalLiteral(BigDecimal value) implements Literal {
        /**
         * Rejects a null value.
         */
        public DecimalLiteral {
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
    record DateLiteral(LocalDate value) implements Literal {
        /**
         * Rejects a null value.
         */
        public DateLiteral {
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
    record InstantLiteral(Instant value) implements Literal {
        /**
         * Rejects a null value.
         */
        public InstantLiteral {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType valueType() {
            return ValueType.INSTANT;
        }
    }
}
