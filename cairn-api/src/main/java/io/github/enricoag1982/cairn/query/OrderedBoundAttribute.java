package io.github.enricoag1982.cairn.query;

/**
 * An attribute of a binding whose values are in an order (capability {@code queries}): it adds the order comparisons,
 * minimum and maximum to what every {@link BoundAttribute} has.
 *
 * @param <T> the Java type of the attribute's values
 */
public sealed class OrderedBoundAttribute<T> extends BoundAttribute<T> permits NumericBoundAttribute {

    OrderedBoundAttribute(OrderedAttributeRef<T> reference, String binding) {
        super(reference, binding);
    }

    /**
     * Holds if the attribute is less than a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition lt(T value) {
        return Condition.lt(operand(), literal(value));
    }

    /**
     * Holds if the attribute is less than or equal to a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition le(T value) {
        return Condition.le(operand(), literal(value));
    }

    /**
     * Holds if the attribute is greater than a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition gt(T value) {
        return Condition.gt(operand(), literal(value));
    }

    /**
     * Holds if the attribute is greater than or equal to a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition ge(T value) {
        return Condition.ge(operand(), literal(value));
    }

    /**
     * Holds if the attribute is less than the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition lt(OrderedBoundAttribute<T> other) {
        return Condition.lt(operand(), other.operand());
    }

    /**
     * Holds if the attribute is less than or equal to the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition le(OrderedBoundAttribute<T> other) {
        return Condition.le(operand(), other.operand());
    }

    /**
     * Holds if the attribute is greater than the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition gt(OrderedBoundAttribute<T> other) {
        return Condition.gt(operand(), other.operand());
    }

    /**
     * Holds if the attribute is greater than or equal to the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition ge(OrderedBoundAttribute<T> other) {
        return Condition.ge(operand(), other.operand());
    }

    /**
     * Finds the smallest value of the attribute in each group, of the attribute's value type.
     *
     * @param name the name of the aggregate's column
     * @return the aggregate
     */
    public Aggregate min(String name) {
        return Aggregate.min(operand(), name);
    }

    /**
     * Finds the largest value of the attribute in each group, of the attribute's value type.
     *
     * @param name the name of the aggregate's column
     * @return the aggregate
     */
    public Aggregate max(String name) {
        return Aggregate.max(operand(), name);
    }

    private Operand.Literal literal(T value) {
        return new Operand.Literal(reference().value(value));
    }
}
