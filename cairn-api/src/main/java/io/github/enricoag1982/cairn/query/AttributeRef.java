package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * A reference to an attribute by its name, typed with the Java type of its values (ADR-0023, ADR-0021, capability
 * {@code queries}). It gives conditions, columns, group keys, orders and the arguments of aggregates compile-time types:
 * a condition on a decimal attribute takes a {@link BigDecimal}, and a string attribute cannot be summed. They build the
 * same records as the untyped builder, so a typed query is an ordinary {@link Query}.
 *
 * <p>An attribute reference belongs to no binding. {@link #of(String)} says which binding's attribute it is, and the
 * result builds the parts of a query:
 *
 * <pre>{@code
 * AttributeRef<BigDecimal> balance = AttributeRef.decimal("balance");
 * Query query = Query.from("Account", "a")
 *         .where(balance.of("a").gt(new BigDecimal("1200")))
 *         .orderBy(balance.of("a").descending())
 *         .limit(1)
 *         .build();
 * }</pre>
 *
 * <p>The family has three levels, so that the methods that do not make sense for a type are not there: every reference
 * has equality, tests for a value and a count; an {@link OrderedAttributeRef} (strings, numbers, dates and instants)
 * adds the order comparisons, minimum and maximum; a {@link NumericAttributeRef} adds sum and average. Booleans can only
 * be tested for equality. The factories name the value type of the attribute. A reference does not check that the
 * attribute exists or has the value type: the view that runs the query checks the names against its schema.
 *
 * @param <T> the Java type of the attribute's values
 */
public sealed class AttributeRef<T> permits OrderedAttributeRef {

    private final String name;
    private final ValueType valueType;
    private final Function<T, Value> toValue;

    AttributeRef(String name, ValueType valueType, Function<T, Value> toValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.toValue = Objects.requireNonNull(toValue, "toValue");
    }

    /**
     * References a string attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static OrderedAttributeRef<String> string(String name) {
        return new OrderedAttributeRef<>(name, ValueType.STRING, Value::of);
    }

    /**
     * References a boolean attribute, which can be tested for equality only.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static AttributeRef<Boolean> bool(String name) {
        return new AttributeRef<>(name, ValueType.BOOLEAN, value -> Value.of(value.booleanValue()));
    }

    /**
     * References an int attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static NumericAttributeRef<Integer> integer(String name) {
        return new NumericAttributeRef<>(name, ValueType.INT, value -> Value.of(value.intValue()));
    }

    /**
     * References a long attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static NumericAttributeRef<Long> longInteger(String name) {
        return new NumericAttributeRef<>(name, ValueType.LONG, value -> Value.of(value.longValue()));
    }

    /**
     * References a decimal attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static NumericAttributeRef<BigDecimal> decimal(String name) {
        return new NumericAttributeRef<>(name, ValueType.DECIMAL, Value::of);
    }

    /**
     * References a date attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static OrderedAttributeRef<LocalDate> date(String name) {
        return new OrderedAttributeRef<>(name, ValueType.DATE, Value::of);
    }

    /**
     * References an instant attribute.
     *
     * @param name the name of the attribute, or an alias of it
     * @return the reference
     */
    public static OrderedAttributeRef<Instant> instant(String name) {
        return new OrderedAttributeRef<>(name, ValueType.INSTANT, Value::of);
    }

    /**
     * The name of the attribute, as it was given.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The value type of the attribute that the reference stands for.
     *
     * @return the value type
     */
    public ValueType valueType() {
        return valueType;
    }

    /**
     * Refers to this attribute of a binding of a query.
     *
     * @param binding the name of the binding of an entity or a relationship
     * @return the attribute of the binding, which builds conditions, columns, keys, orders and aggregates
     */
    public BoundAttribute<T> of(String binding) {
        return new BoundAttribute<>(this, binding);
    }

    /** The value that stands for a Java value of this reference. */
    Value value(T value) {
        return toValue.apply(Objects.requireNonNull(value, "value"));
    }

    @Override
    public String toString() {
        return valueType.name().toLowerCase(Locale.ROOT) + " attribute " + name;
    }
}
