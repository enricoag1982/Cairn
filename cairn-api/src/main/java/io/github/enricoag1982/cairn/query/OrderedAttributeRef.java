package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.function.Function;

/**
 * A reference to an attribute whose values are in an order: strings (by code point), numbers, dates and instants
 * (capability {@code queries}). Its attribute of a binding adds the order comparisons, minimum and maximum to what every
 * {@link AttributeRef} has.
 *
 * @param <T> the Java type of the attribute's values
 */
public sealed class OrderedAttributeRef<T> extends AttributeRef<T> permits NumericAttributeRef {

    OrderedAttributeRef(String name, ValueType valueType, Function<T, Value> toValue) {
        super(name, valueType, toValue);
    }

    @Override
    public OrderedBoundAttribute<T> of(String binding) {
        return new OrderedBoundAttribute<>(this, binding);
    }
}
