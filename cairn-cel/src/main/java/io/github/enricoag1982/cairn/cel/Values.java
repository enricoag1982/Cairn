package io.github.enricoag1982.cairn.cel;

import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.NullValue;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
     * @throws ExpressionEvaluationException if the value does not fit the attribute's value type
     */
    static Object celValue(String attribute, ValueType type, Value value) throws ExpressionEvaluationException {
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
            throw new ExpressionEvaluationException("The value of the attribute " + attribute + " is "
                    + value.valueType().name().toLowerCase(Locale.ROOT) + ", which does not fit its "
                    + type.name().toLowerCase(Locale.ROOT) + " type.");
        }
        return converted;
    }

    /**
     * The value that a derivation's result stands for, as the attribute's value type takes it (design D2 of
     * {@code add-derived-attributes}): a string for a string, a bool for a boolean, an integer for an int, which must lie
     * in the range of a Java {@code int}, and for a long, a double for a decimal, converted with
     * {@link BigDecimal#valueOf(double)}, which does not take NaN or an infinity, and a timestamp for an instant and, at
     * 00:00 UTC, for a date. {@code null} is no value.
     *
     * @param type   the value type of the derived attribute
     * @param result what the program evaluated to, as CEL for Java gives it
     * @return the value, or empty for {@code null}
     * @throws ExpressionEvaluationException if the result does not fit the value type
     */
    static Optional<Value> derived(ValueType type, Object result) throws ExpressionEvaluationException {
        if (result == null || result instanceof NullValue) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case STRING -> result instanceof String text ? Value.of(text) : mismatch(type, result);
            case BOOLEAN -> result instanceof Boolean bool ? Value.of(bool.booleanValue()) : mismatch(type, result);
            case INT -> result instanceof Long number ? intValue(number) : mismatch(type, result);
            case LONG -> result instanceof Long number ? Value.of(number.longValue()) : mismatch(type, result);
            case DECIMAL -> result instanceof Double number ? decimalValue(number) : mismatch(type, result);
            case INSTANT -> result instanceof Instant instant ? Value.of(instant) : mismatch(type, result);
            case DATE -> result instanceof Instant instant ? dateValue(instant) : mismatch(type, result);
        });
    }

    private static Value intValue(long number) throws ExpressionEvaluationException {
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ExpressionEvaluationException("The result " + number + " is outside the range of an int, which is "
                    + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE + ".");
        }
        return Value.of((int) number);
    }

    private static Value decimalValue(double number) throws ExpressionEvaluationException {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new ExpressionEvaluationException("The result " + number + " is not a number that a decimal can hold.");
        }
        return Value.of(BigDecimal.valueOf(number));
    }

    private static Value dateValue(Instant instant) throws ExpressionEvaluationException {
        if (!instant.equals(instant.truncatedTo(ChronoUnit.DAYS))) {
            throw new ExpressionEvaluationException("The result " + instant + " is not the start of a day in UTC, which"
                    + " a date needs.");
        }
        try {
            return Value.of(instant.atOffset(ZoneOffset.UTC).toLocalDate());
        } catch (DateTimeException e) {
            throw new ExpressionEvaluationException("The result " + instant + " is not a date that Cairn can hold.", e);
        }
    }

    private static Value mismatch(ValueType type, Object result) throws ExpressionEvaluationException {
        throw new ExpressionEvaluationException("The result of the derivation is " + describe(result) + ", but the "
                + type.name().toLowerCase(Locale.ROOT) + " attribute needs " + needs(type) + ".");
    }

    /** What the result of a derivation must be for a value type, with its article. */
    private static String needs(ValueType type) {
        return switch (type) {
            case STRING -> "a string";
            case BOOLEAN -> "a bool";
            case INT, LONG -> "an integer";
            case DECIMAL -> "a double";
            case INSTANT -> "a timestamp";
            case DATE -> "a timestamp at the start of a day in UTC";
        };
    }

    /** What the result of a program is, as a person reads CEL's types. */
    private static String describe(Object result) {
        return switch (result) {
            case String text -> "a string";
            case Boolean bool -> "a bool";
            case Long number -> "an integer";
            case Double number -> "a double";
            case Instant instant -> "a timestamp";
            case Duration duration -> "a duration";
            case List<?> list -> "a list";
            case Map<?, ?> map -> "a map";
            default -> "a " + result.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
    }
}
