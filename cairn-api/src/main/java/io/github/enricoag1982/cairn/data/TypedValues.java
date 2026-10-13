package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the values of an {@link EntityState} or a {@link RelationshipState} as Java values (ADR-0021, capability
 * {@code java-types}). The code that the generator writes uses it to fill the snapshot record of a type; writing uses
 * {@link Value#of(String)} and its overloads.
 *
 * <p>The values of a state are keyed by attribute name, and an attribute without a value is absent. A method for a
 * required attribute, named {@code required...}, fails if the value is absent, and a method for an optional attribute,
 * named {@code optional...}, returns an empty {@link Optional}. Both fail if the value has another value type than the
 * method reads: such a state does not fit the generated type, which is a programming error, so the failure is an
 * {@link IllegalArgumentException} whose message names the attribute. There is a pair of methods for each value type:
 * string, boolean, int, long, decimal, date and instant.
 */
public final class TypedValues {

    private TypedValues() {
    }

    /**
     * Reads a required string value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static String requiredString(Map<String, Value> values, String attribute) {
        return ((Value.StringValue) required(values, attribute, ValueType.STRING)).value();
    }

    /**
     * Reads an optional string value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<String> optionalString(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.STRING).map(value -> ((Value.StringValue) value).value());
    }

    /**
     * Reads a required boolean value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static boolean requiredBoolean(Map<String, Value> values, String attribute) {
        return ((Value.BooleanValue) required(values, attribute, ValueType.BOOLEAN)).value();
    }

    /**
     * Reads an optional boolean value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<Boolean> optionalBoolean(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.BOOLEAN).map(value -> ((Value.BooleanValue) value).value());
    }

    /**
     * Reads a required int value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static int requiredInt(Map<String, Value> values, String attribute) {
        return ((Value.IntValue) required(values, attribute, ValueType.INT)).value();
    }

    /**
     * Reads an optional int value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<Integer> optionalInt(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.INT).map(value -> ((Value.IntValue) value).value());
    }

    /**
     * Reads a required long value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static long requiredLong(Map<String, Value> values, String attribute) {
        return ((Value.LongValue) required(values, attribute, ValueType.LONG)).value();
    }

    /**
     * Reads an optional long value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<Long> optionalLong(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.LONG).map(value -> ((Value.LongValue) value).value());
    }

    /**
     * Reads a required decimal value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static BigDecimal requiredDecimal(Map<String, Value> values, String attribute) {
        return ((Value.DecimalValue) required(values, attribute, ValueType.DECIMAL)).value();
    }

    /**
     * Reads an optional decimal value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<BigDecimal> optionalDecimal(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.DECIMAL).map(value -> ((Value.DecimalValue) value).value());
    }

    /**
     * Reads a required date value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static LocalDate requiredDate(Map<String, Value> values, String attribute) {
        return ((Value.DateValue) required(values, attribute, ValueType.DATE)).value();
    }

    /**
     * Reads an optional date value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<LocalDate> optionalDate(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.DATE).map(value -> ((Value.DateValue) value).value());
    }

    /**
     * Reads a required instant value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value
     * @throws IllegalArgumentException if the attribute has no value, or a value of another type
     */
    public static Instant requiredInstant(Map<String, Value> values, String attribute) {
        return ((Value.InstantValue) required(values, attribute, ValueType.INSTANT)).value();
    }

    /**
     * Reads an optional instant value.
     *
     * @param values    the values of a state
     * @param attribute the attribute's name
     * @return the value, or empty if the attribute has none
     * @throws IllegalArgumentException if the attribute has a value of another type
     */
    public static Optional<Instant> optionalInstant(Map<String, Value> values, String attribute) {
        return optional(values, attribute, ValueType.INSTANT).map(value -> ((Value.InstantValue) value).value());
    }

    private static Value required(Map<String, Value> values, String attribute, ValueType expected) {
        return optional(values, attribute, expected).orElseThrow(() -> new IllegalArgumentException(
                "The attribute " + attribute + " has no value, but its generated type requires one."));
    }

    private static Optional<Value> optional(Map<String, Value> values, String attribute, ValueType expected) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(attribute, "attribute");
        Value value = values.get(attribute);
        if (value != null && value.valueType() != expected) {
            throw new IllegalArgumentException("The attribute " + attribute + " has a value of the type "
                    + value.valueType() + ", but its generated type reads the type " + expected + ".");
        }
        return Optional.ofNullable(value);
    }
}
