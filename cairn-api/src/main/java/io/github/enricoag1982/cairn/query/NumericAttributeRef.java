package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.function.Function;

/**
 * A reference to an attribute whose values are numbers: int, long and decimal (capability {@code queries}). Its attribute
 * of a binding adds sum and average to what every {@link OrderedAttributeRef} has.
 *
 * @param <T> the Java type of the attribute's values
 */
public final class NumericAttributeRef<T> extends OrderedAttributeRef<T> {

    NumericAttributeRef(String name, ValueType valueType, Function<T, Value> toValue) {
        super(name, valueType, toValue);
    }

    @Override
    public NumericBoundAttribute<T> of(String binding) {
        return new NumericBoundAttribute<>(this, binding);
    }
}
