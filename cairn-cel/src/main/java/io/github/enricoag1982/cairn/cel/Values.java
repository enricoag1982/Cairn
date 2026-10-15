package io.github.enricoag1982.cairn.cel;

import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import io.github.enricoag1982.cairn.expressions.CheckEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * How Cairn's values and value types map to CEL (design D3 of {@code add-cel-expressions}): strings and allowed values
 * are strings, booleans are booleans, int and long values are integers, decimals are doubles, a date is a timestamp at
 * 00:00 UTC of the day, and an instant is a timestamp.
 *
 * <p>A value is converted by the value type of its attribute, not by its own: an int stored for a long or decimal
 * attribute is an integer or a double, as the expression was compiled for.
 */
final class Values {

    private Values() {
    }

    /** The CEL type of the values of an attribute of a value type. */
    static CelType celType(ValueType type) {
        return switch (type) {
            case STRING -> SimpleType.STRING;
            case BOOLEAN -> SimpleType.BOOL;
            case INT, LONG -> SimpleType.INT;
            case DECIMAL -> SimpleType.DOUBLE;
            case DATE, INSTANT -> SimpleType.TIMESTAMP;
        };
    }

    /**
     * The CEL value of a value of an attribute.
     *
     * @throws CheckEvaluationException if the value does not fit the attribute's value type
     */
    static Object celValue(String attribute, ValueType type, Value value) throws CheckEvaluationException {
        Object converted = switch (type) {
            case STRING -> value instanceof Value.StringValue text ? text.value() : null;
            case BOOLEAN -> value instanceof Value.BooleanValue bool ? Boolean.valueOf(bool.value()) : null;
            case INT -> value instanceof Value.IntValue number ? Long.valueOf(number.value()) : null;
            case LONG -> switch (value) {
                case Value.IntValue number -> Long.valueOf(number.value());
                case Value.LongValue number -> Long.valueOf(number.value());
                default -> null;
            };
            case DECIMAL -> switch (value) {
                case Value.IntValue number -> Double.valueOf(number.value());
                case Value.LongValue number -> Double.valueOf(number.value());
                case Value.DecimalValue number -> Double.valueOf(number.value().doubleValue());
                default -> null;
            };
            case DATE -> value instanceof Value.DateValue date
                    ? date.value().atStartOfDay(ZoneOffset.UTC).toInstant() : null;
            case INSTANT -> value instanceof Value.InstantValue instant ? instant.value() : null;
        };
        if (converted == null) {
            throw new CheckEvaluationException("The value of the attribute " + attribute + " is "
                    + value.valueType().name().toLowerCase(Locale.ROOT) + ", which does not fit its "
                    + type.name().toLowerCase(Locale.ROOT) + " type.");
        }
        return converted;
    }
}
