package io.github.enricoag1982.cairn.schema;

/**
 * The kind of value an attribute holds (capability {@code type-model}).
 */
public enum ValueType {
    /** Text. */
    STRING,
    /** True or false. */
    BOOLEAN,
    /** A 32-bit signed integer. */
    INT,
    /** A 64-bit signed integer. */
    LONG,
    /** A decimal number of arbitrary precision. */
    DECIMAL,
    /** A calendar date without a time zone. */
    DATE,
    /** A point on the UTC time line. */
    INSTANT;

    /**
     * Tells whether values of this type are ordered, so that an attribute of this type may declare a range.
     *
     * @return {@code true} for int, long, decimal, date and instant
     */
    public boolean isOrdered() {
        return this != STRING && this != BOOLEAN;
    }
}
