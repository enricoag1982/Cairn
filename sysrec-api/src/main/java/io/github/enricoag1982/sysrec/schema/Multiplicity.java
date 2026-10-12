package io.github.enricoag1982.sysrec.schema;

/**
 * How many relationships of one type an entity can have at one end of a relationship type: a minimum of 0 or 1
 * and a maximum of 1 or unbounded (capability {@code type-model}).
 *
 * @param minimum 0 or 1
 * @param maximum 1, or {@link #UNBOUNDED}
 */
public record Multiplicity(int minimum, int maximum) {

    /** The maximum that means "no limit". */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    /** At most one. */
    public static final Multiplicity ZERO_OR_ONE = new Multiplicity(0, 1);
    /** Exactly one. */
    public static final Multiplicity EXACTLY_ONE = new Multiplicity(1, 1);
    /** Any number. */
    public static final Multiplicity ZERO_OR_MORE = new Multiplicity(0, UNBOUNDED);
    /** At least one. */
    public static final Multiplicity ONE_OR_MORE = new Multiplicity(1, UNBOUNDED);

    /**
     * Checks that the minimum is 0 or 1 and the maximum is 1 or unbounded.
     *
     * @throws IllegalArgumentException if a bound is outside those values
     */
    public Multiplicity {
        if (minimum != 0 && minimum != 1) {
            throw new IllegalArgumentException("minimum must be 0 or 1: " + minimum);
        }
        if (maximum != 1 && maximum != UNBOUNDED) {
            throw new IllegalArgumentException("maximum must be 1 or unbounded: " + maximum);
        }
    }

    /**
     * Tells whether the maximum is unbounded.
     *
     * @return {@code true} if there is no maximum
     */
    public boolean isUnbounded() {
        return maximum == UNBOUNDED;
    }

    @Override
    public String toString() {
        return minimum + ".." + (isUnbounded() ? "*" : String.valueOf(maximum));
    }
}
