package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads the scalars of a schema document (design D6).
 *
 * <p>A scalar has a YAML type: the core schema resolves a plain scalar to a string, boolean, integer, float or null,
 * and a quoted scalar is always a string. A literal gets the value type of its attribute if the scalar fits it.
 * Numbers are read from the text, so a decimal never passes through a {@code double}.
 *
 * <p>A number must be written as in JSON, so that every document means the same read as JSON: the forms that only YAML
 * knows ({@code 0x10}, {@code 0o17}, {@code +1}, {@code .5}, {@code 1.}, {@code 01}, {@code .inf}) are numbers for YAML
 * but never numbers of a schema document ({@link #isYamlOnlyNumber}).
 */
final class Scalars {

    private static final ScalarResolver RESOLVER = new CoreSchema().getScalarResolver();

    private static final Pattern JSON_INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final Pattern YAML_NUMBER = Pattern.compile("([-+]?)([0-9]*)(?:\\.([0-9]*))?([eE][-+]?[0-9]+)?");

    private Scalars() {
    }

    /**
     * The YAML type of a scalar: a plain scalar is resolved by its text with the core schema, and any other scalar
     * (in quotes, or a block) is a string. A tag that was written is ignored here, because it is a problem of its own.
     */
    static Tag typeOf(ScalarNode scalar) {
        return scalar.isPlain() ? RESOLVER.resolve(scalar.getValue(), true) : Tag.STR;
    }

    /** Tells whether a scalar is a string: written in quotes, or plain text that is not another YAML type. */
    static boolean isString(ScalarNode scalar) {
        return Tag.STR.equals(typeOf(scalar));
    }

    /** Tells whether YAML reads a scalar as an integer, in a form of JSON or not: {@code 0x10} is one. */
    static boolean isInteger(ScalarNode scalar) {
        return Tag.INT.equals(typeOf(scalar));
    }

    /** Tells whether a scalar is a plain {@code true} or {@code false}. */
    static boolean isBoolean(ScalarNode scalar) {
        return Tag.BOOL.equals(typeOf(scalar));
    }

    /** Tells whether a scalar is a plain null: {@code null}, {@code ~} or nothing. */
    static boolean isNull(ScalarNode scalar) {
        return Tag.NULL.equals(typeOf(scalar));
    }

    /** Tells whether YAML reads a scalar as a float, in a form of JSON or not: {@code .5} and {@code .inf} are some. */
    static boolean isFloat(ScalarNode scalar) {
        return Tag.FLOAT.equals(typeOf(scalar));
    }

    /** Tells whether a scalar is a plain integer written as in JSON: {@code -?(0|[1-9][0-9]*)}. */
    static boolean isJsonInteger(ScalarNode scalar) {
        return scalar.isPlain() && JSON_INTEGER.matcher(scalar.getValue()).matches();
    }

    /**
     * Tells whether a scalar is a plain number written as in JSON: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}.
     */
    static boolean isJsonNumber(ScalarNode scalar) {
        return scalar.isPlain() && JSON_NUMBER.matcher(scalar.getValue()).matches();
    }

    /**
     * Tells whether YAML reads a scalar as a number that JSON does not write like that: hexadecimal and octal integers,
     * a leading plus or zero, a bare decimal point, infinity and not a number.
     */
    static boolean isYamlOnlyNumber(ScalarNode scalar) {
        return (isInteger(scalar) || isFloat(scalar)) && !isJsonNumber(scalar);
    }

    /**
     * The JSON form of a number that only YAML writes like that, to show an author what to write: {@code 0x10} is
     * {@code 16}, {@code .5} is {@code 0.5} and {@code +01} is {@code 1}.
     *
     * @return the number as JSON writes it, or empty if JSON has none (infinity, not a number)
     */
    static Optional<String> jsonForm(ScalarNode scalar) {
        if (!isYamlOnlyNumber(scalar)) {
            return Optional.empty();
        }
        String text = scalar.getValue();
        String unsigned = text.startsWith("-") || text.startsWith("+") ? text.substring(1) : text;
        String sign = text.startsWith("-") ? "-" : "";
        if (unsigned.startsWith("0x") || unsigned.startsWith("0o")) {
            return Optional.of(sign + new BigInteger(unsigned.substring(2), unsigned.startsWith("0x") ? 16 : 8));
        }
        Matcher number = YAML_NUMBER.matcher(text);
        if (!number.matches()) {
            return Optional.empty();
        }
        String whole = number.group(2).replaceFirst("^0+(?=[0-9])", "");
        String fraction = number.group(3) == null || number.group(3).isEmpty() ? "" : "." + number.group(3);
        String exponent = number.group(4) == null ? "" : number.group(4);
        return Optional.of(sign + (whole.isEmpty() ? "0" : whole) + fraction + exponent);
    }

    /** The instant that a string scalar spells in ISO 8601 form, or empty for any other scalar. */
    static Optional<Instant> instantValue(ScalarNode scalar) {
        return isString(scalar) ? instant(scalar.getValue()) : Optional.empty();
    }

    /** The value of a scalar that {@link #isBoolean} is true for. */
    static boolean booleanValue(ScalarNode scalar) {
        return scalar.getValue().toLowerCase(Locale.ROOT).equals("true");
    }

    /** The value of a scalar that {@link #isJsonInteger} is true for. */
    static BigInteger integerValue(ScalarNode scalar) {
        return new BigInteger(scalar.getValue());
    }

    /** The value of a scalar as an {@code int}, if it is an integer written as in JSON that fits. */
    static Optional<Integer> intValue(ScalarNode scalar) {
        return isJsonInteger(scalar) ? toInt(integerValue(scalar)) : Optional.empty();
    }

    /**
     * Reads a scalar as a literal.
     *
     * <p>If the value type is known and the scalar fits it, the literal has that type: an int, long or decimal from a
     * plain number written as in JSON, a boolean from plain {@code true} or {@code false}, a string from a string, a
     * date or instant from a string in ISO 8601 form. Otherwise the literal has the scalar's own YAML type: a string, a
     * boolean, an integer as an int, a long or, if it is larger, a decimal, or a number with a fraction or exponent as
     * a decimal. Validating the definition then reports the mismatch.
     *
     * @param scalar the scalar
     * @param type   the value type of the attribute, or {@code null} if it is unknown
     * @return the literal, or empty if the scalar can be no literal: null, or a number that is not written as in JSON
     */
    static Optional<Value> literal(ScalarNode scalar, ValueType type) {
        Optional<Value> fitting = type == null ? Optional.empty() : fitting(scalar, type);
        return fitting.isPresent() ? fitting : own(scalar);
    }

    private static Optional<Value> fitting(ScalarNode scalar, ValueType type) {
        return switch (type) {
            case STRING -> isString(scalar) ? Optional.of(Value.of(scalar.getValue())) : Optional.empty();
            case BOOLEAN -> isBoolean(scalar) ? Optional.of(Value.of(booleanValue(scalar))) : Optional.empty();
            case INT -> intValue(scalar).map(Value::of);
            case LONG -> isJsonInteger(scalar) ? toLong(integerValue(scalar)).map(Value::of) : Optional.empty();
            case DECIMAL -> decimal(scalar).map(Value::of);
            case DATE -> isString(scalar) ? date(scalar.getValue()).map(Value::of) : Optional.empty();
            case INSTANT -> isString(scalar) ? instant(scalar.getValue()).map(Value::of) : Optional.empty();
        };
    }

    private static Optional<Value> own(ScalarNode scalar) {
        if (isNull(scalar) || isYamlOnlyNumber(scalar)) {
            return Optional.empty();
        }
        if (isBoolean(scalar)) {
            return Optional.of(Value.of(booleanValue(scalar)));
        }
        if (isJsonInteger(scalar)) {
            BigInteger value = integerValue(scalar);
            Optional<Integer> asInt = toInt(value);
            if (asInt.isPresent()) {
                return Optional.of(Value.of(asInt.get()));
            }
            Optional<Long> asLong = toLong(value);
            return asLong.isPresent() ? Optional.of(Value.of(asLong.get())) : Optional.of(Value.of(new BigDecimal(value)));
        }
        if (isJsonNumber(scalar)) {
            return decimal(scalar).map(Value::of);
        }
        return Optional.of(Value.of(scalar.getValue()));
    }

    /** The value of a number written as in JSON as a decimal, read from its text; empty for any other scalar. */
    private static Optional<BigDecimal> decimal(ScalarNode scalar) {
        if (!isJsonNumber(scalar)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(scalar.getValue()));
        } catch (NumberFormatException e) {
            // An exponent beyond what BigDecimal can hold: the number is no literal.
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> date(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> toInt(BigInteger value) {
        return value.bitLength() < Integer.SIZE ? Optional.of(value.intValue()) : Optional.empty();
    }

    private static Optional<Long> toLong(BigInteger value) {
        return value.bitLength() < Long.SIZE ? Optional.of(value.longValue()) : Optional.empty();
    }
}
