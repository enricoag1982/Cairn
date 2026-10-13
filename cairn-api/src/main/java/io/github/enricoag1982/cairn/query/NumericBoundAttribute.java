package io.github.enricoag1982.cairn.query;

/**
 * An attribute of a binding whose values are numbers (capability {@code queries}): it adds sum and average to what every
 * {@link OrderedBoundAttribute} has.
 *
 * @param <T> the Java type of the attribute's values
 */
public final class NumericBoundAttribute<T> extends OrderedBoundAttribute<T> {

    NumericBoundAttribute(NumericAttributeRef<T> reference, String binding) {
        super(reference, binding);
    }

    /**
     * Sums the values of the attribute in each group, as an exact decimal; a group without values has the sum 0.
     *
     * @param name the name of the aggregate's column
     * @return the aggregate
     */
    public Aggregate sum(String name) {
        return Aggregate.sum(operand(), name);
    }

    /**
     * Averages the values of the attribute in each group, as a decimal rounded to 34 significant digits; a group without
     * values has no average.
     *
     * @param name the name of the aggregate's column
     * @return the aggregate
     */
    public Aggregate avg(String name) {
        return Aggregate.avg(operand(), name);
    }
}
