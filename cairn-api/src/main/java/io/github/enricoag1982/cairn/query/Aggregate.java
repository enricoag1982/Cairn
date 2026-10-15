package io.github.enricoag1982.cairn.query;

import java.util.Objects;
import java.util.Optional;

/**
 * A value computed for each group of rows (ADR-0023, capability {@code queries}). Missing values are left out.
 *
 * <p>{@link Function#COUNT} without an attribute counts the rows of the group, and with one the values; its result is a
 * long, and 0 for a group without values. {@link Function#SUM} of numbers is an exact decimal, 0 without values.
 * {@link Function#AVG} of numbers is a decimal rounded to 34 significant digits, and {@link Function#MIN} and
 * {@link Function#MAX} keep the value type of the attribute and work on numbers, dates, instants and strings; without
 * values these three have no value.
 *
 * @param function the function
 * @param of       the attribute to aggregate; empty for a count of rows, and required for the other functions
 * @param name     the name of the column that the aggregate gives to a grouped row
 */
public record Aggregate(Function function, Optional<Operand.AttributeOf> of, String name) {

    /** What an aggregate computes. */
    public enum Function {
        /** The number of rows, or of values. */
        COUNT,
        /** The sum of numbers. */
        SUM,
        /** The smallest value. */
        MIN,
        /** The largest value. */
        MAX,
        /** The average of numbers. */
        AVG
    }

    /**
     * Rejects null components.
     */
    public Aggregate {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(of, "of");
        Objects.requireNonNull(name, "name");
    }

    /**
     * Counts the rows of each group.
     *
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate count(String name) {
        return new Aggregate(Function.COUNT, Optional.empty(), name);
    }

    /**
     * Counts the values of an attribute in each group.
     *
     * @param of   the attribute
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate count(Operand.AttributeOf of, String name) {
        return new Aggregate(Function.COUNT, Optional.of(Objects.requireNonNull(of, "of")), name);
    }

    /**
     * Sums the values of a numeric attribute in each group.
     *
     * @param of   the attribute
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate sum(Operand.AttributeOf of, String name) {
        return new Aggregate(Function.SUM, Optional.of(Objects.requireNonNull(of, "of")), name);
    }

    /**
     * Finds the smallest value of an attribute in each group.
     *
     * @param of   the attribute
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate min(Operand.AttributeOf of, String name) {
        return new Aggregate(Function.MIN, Optional.of(Objects.requireNonNull(of, "of")), name);
    }

    /**
     * Finds the largest value of an attribute in each group.
     *
     * @param of   the attribute
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate max(Operand.AttributeOf of, String name) {
        return new Aggregate(Function.MAX, Optional.of(Objects.requireNonNull(of, "of")), name);
    }

    /**
     * Averages the values of a numeric attribute in each group.
     *
     * @param of   the attribute
     * @param name the name of the column
     * @return the aggregate
     */
    public static Aggregate avg(Operand.AttributeOf of, String name) {
        return new Aggregate(Function.AVG, Optional.of(Objects.requireNonNull(of, "of")), name);
    }
}
