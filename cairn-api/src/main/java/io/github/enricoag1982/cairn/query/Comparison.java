package io.github.enricoag1982.cairn.query;

/**
 * How a {@link Condition.Compare} compares its two operands (capability {@code queries}). A comparison with a value that
 * is missing, or of a binding that is empty, is false, whichever comparison it is.
 */
public enum Comparison {
    /** The operands are equal; numbers are equal by value, whatever their value types. */
    EQ,
    /** The operands are not equal. */
    NE,
    /** The left operand is less than the right one; not for booleans. */
    LT,
    /** The left operand is less than or equal to the right one; not for booleans. */
    LE,
    /** The left operand is greater than the right one; not for booleans. */
    GT,
    /** The left operand is greater than or equal to the right one; not for booleans. */
    GE;

    /**
     * Tells whether this comparison needs the operands to be in an order.
     *
     * @return {@code true} for everything but {@link #EQ} and {@link #NE}
     */
    public boolean needsOrder() {
        return this != EQ && this != NE;
    }
}
