package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;

/**
 * Conversion of values to the value type of their attribute (design D5): an int widens to a long or a decimal, and a
 * long to a decimal. Stored values are converted when they are written, and again when they are read, because the
 * attribute may have been widened since.
 */
final class Conversions {

    private Conversions() {
    }

    /**
     * Converts a value to a value type. A value that does not fit the type is returned as it is, so that validation can
     * report it.
     */
    static Value convert(Value value, ValueType type) {
        if (value.valueType() == type || !Values.fits(value, type)) {
            return value;
        }
        return switch (value) {
            case Value.IntValue(int number) -> type == ValueType.LONG
                    ? new Value.LongValue(number)
                    : new Value.DecimalValue(BigDecimal.valueOf(number));
            case Value.LongValue(long number) -> new Value.DecimalValue(BigDecimal.valueOf(number));
            default -> value;
        };
    }

    /** Tells whether two values are the same: numbers by their numeric value, anything else by equality. */
    static boolean same(Value first, Value second) {
        var order = Values.compare(first, second);
        return order.isPresent() ? order.getAsInt() == 0 : first.equals(second);
    }
}
