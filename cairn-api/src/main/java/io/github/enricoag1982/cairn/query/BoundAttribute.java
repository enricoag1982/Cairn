package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An attribute of a binding of a query, typed with the Java type of its values (capability {@code queries}): the
 * operand that builds conditions from values of {@code T}, and serves as a column, a group key, an order and the
 * argument of a count. {@link OrderedBoundAttribute} and {@link NumericBoundAttribute} add what their types allow.
 *
 * <p>The conditions, columns, keys, orders and aggregates it builds are the records of the untyped builder, such as
 * {@link Condition.Compare} and {@link Column.AttributeColumn}; {@link #operand()} is the {@link Operand} that they
 * contain.
 *
 * @param <T> the Java type of the attribute's values
 */
public sealed class BoundAttribute<T> permits OrderedBoundAttribute {

    private final AttributeRef<T> reference;
    private final String binding;

    BoundAttribute(AttributeRef<T> reference, String binding) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /**
     * The reference that this is the attribute of a binding of.
     *
     * @return the reference
     */
    public AttributeRef<T> reference() {
        return reference;
    }

    /**
     * The name of the binding.
     *
     * @return the name
     */
    public String binding() {
        return binding;
    }

    /**
     * The operand that stands for the attribute of the binding.
     *
     * @return the operand
     */
    public Operand.AttributeOf operand() {
        return new Operand.AttributeOf(binding, reference.name());
    }

    /**
     * Holds if the attribute equals a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition eq(T value) {
        return Condition.eq(operand(), new Operand.Literal(reference.value(value)));
    }

    /**
     * Holds if the attribute does not equal a value.
     *
     * @param value the value
     * @return the condition
     */
    public Condition ne(T value) {
        return Condition.ne(operand(), new Operand.Literal(reference.value(value)));
    }

    /**
     * Holds if the attribute equals the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition eq(BoundAttribute<T> other) {
        return Condition.eq(operand(), other.operand());
    }

    /**
     * Holds if the attribute does not equal the attribute of another binding.
     *
     * @param other the attribute of the other binding
     * @return the condition
     */
    public Condition ne(BoundAttribute<T> other) {
        return Condition.ne(operand(), other.operand());
    }

    /**
     * Holds if the attribute equals one of some values.
     *
     * @param values the values
     * @return the condition
     */
    public Condition in(Collection<? extends T> values) {
        List<Value> converted = values.stream().<Value>map(reference::value).toList();
        return Condition.in(operand(), converted);
    }

    /**
     * Holds if the attribute has a value.
     *
     * @return the condition
     */
    public Condition hasValue() {
        return Condition.hasValue(binding, reference.name());
    }

    /**
     * The attribute as a column, named like {@code a.balance}.
     *
     * @return the column
     */
    public Column column() {
        return Column.attribute(binding, reference.name());
    }

    /**
     * The attribute as a column.
     *
     * @param name the name of the column
     * @return the column
     */
    public Column column(String name) {
        return Column.attribute(binding, reference.name(), name);
    }

    /**
     * The attribute as a group key, whose column is named like {@code a.balance}.
     *
     * @return the key
     */
    public GroupKey groupKey() {
        return GroupKey.attribute(binding, reference.name());
    }

    /**
     * The attribute as a group key.
     *
     * @param name the name of the column of the key
     * @return the key
     */
    public GroupKey groupKey(String name) {
        return GroupKey.attribute(binding, reference.name(), name);
    }

    /**
     * Orders by the attribute, smallest first, with missing values first.
     *
     * @return the order
     */
    public Order ascending() {
        return Order.ascending(column());
    }

    /**
     * Orders by the attribute, largest first, with missing values last.
     *
     * @return the order
     */
    public Order descending() {
        return Order.descending(column());
    }

    /**
     * Counts the values of the attribute in each group.
     *
     * @param name the name of the aggregate's column
     * @return the aggregate
     */
    public Aggregate count(String name) {
        return Aggregate.count(operand(), name);
    }

    @Override
    public String toString() {
        return binding + "." + reference.name();
    }
}
