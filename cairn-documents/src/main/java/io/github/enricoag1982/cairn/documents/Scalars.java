package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
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
 */
final class Scalars {

    private static final ScalarResolver RESOLVER = new CoreSchema().getScalarResolver();

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

    /** Tells whether a scalar is a plain integer. */
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

    /** Tells whether a scalar is a plain float, including infinity and not a number. */
    static boolean isFloat(ScalarNode scalar) {
        return Tag.FLOAT.equals(typeOf(scalar));
    }

    /** The value of a scalar that {@link #isBoolean} is true for. */
    static boolean booleanValue(ScalarNode scalar) {
        return scalar.getValue().toLowerCase(Locale.ROOT).equals("true");
    }

    /** The value of a scalar that {@link #isInteger} is true for: decimal, {@code 0o} octal or {@code 0x} hexadecimal. */
    static BigInteger integerValue(ScalarNode scalar) {
        String text = scalar.getValue();
        boolean negative = text.startsWith("-");
        String digits = negative || text.startsWith("+") ? text.substring(1) : text;
        BigInteger value;
        if (digits.startsWith("0x")) {
            value = new BigInteger(digits.substring(2), 16);
        } else if (digits.startsWith("0o")) {
            value = new BigInteger(digits.substring(2), 8);
        } else {
            value = new BigInteger(digits);
        }
        return negative ? value.negate() : value;
    }

    /** The value of a scalar as an {@code int}, if it is a plain integer that fits. */
    static Optional<Integer> intValue(ScalarNode scalar) {
        return isInteger(scalar) ? toInt(integerValue(scalar)) : Optional.empty();
    }

    /**
     * Reads a scalar as a literal.
     *
     * <p>If the value type is known and the scalar fits it, the literal has that type: an int, long or decimal from a
     * plain number, a boolean from plain {@code true} or {@code false}, a string from a string, a date or instant from
     * a string in ISO 8601 form. Otherwise the literal has the scalar's own YAML type: a string, a boolean, an
     * integer as an int, a long or, if it is larger, a decimal, or a finite float as a decimal. Validating the
     * definition then reports the mismatch.
     *
     * @param scalar the scalar
     * @param type   the value type of the attribute, or {@code null} if it is unknown
     * @return the literal, or empty if the scalar can be no literal: null, or infinity or not a number
     */
    static Optional<Literal> literal(ScalarNode scalar, ValueType type) {
        Optional<Literal> fitting = type == null ? Optional.empty() : fitting(scalar, type);
        return fitting.isPresent() ? fitting : own(scalar);
    }

    private static Optional<Literal> fitting(ScalarNode scalar, ValueType type) {
        return switch (type) {
            case STRING -> isString(scalar) ? Optional.of(Literal.of(scalar.getValue())) : Optional.empty();
            case BOOLEAN -> isBoolean(scalar) ? Optional.of(Literal.of(booleanValue(scalar))) : Optional.empty();
            case INT -> isInteger(scalar) ? toInt(integerValue(scalar)).map(Literal::of) : Optional.empty();
            case LONG -> isInteger(scalar) ? toLong(integerValue(scalar)).map(Literal::of) : Optional.empty();
            case DECIMAL -> decimal(scalar).map(Literal::of);
            case DATE -> isString(scalar) ? date(scalar.getValue()).map(Literal::of) : Optional.empty();
            case INSTANT -> isString(scalar) ? instant(scalar.getValue()).map(Literal::of) : Optional.empty();
        };
    }

    private static Optional<Literal> own(ScalarNode scalar) {
        if (isNull(scalar)) {
            return Optional.empty();
        }
        if (isBoolean(scalar)) {
            return Optional.of(Literal.of(booleanValue(scalar)));
        }
        if (isInteger(scalar)) {
            BigInteger value = integerValue(scalar);
            Optional<Integer> asInt = toInt(value);
            if (asInt.isPresent()) {
                return Optional.of(Literal.of(asInt.get()));
            }
            Optional<Long> asLong = toLong(value);
            return asLong.isPresent() ? Optional.of(Literal.of(asLong.get())) : Optional.of(Literal.of(new BigDecimal(value)));
        }
        if (isFloat(scalar)) {
            return decimal(scalar).map(Literal::of);
        }
        return Optional.of(Literal.of(scalar.getValue()));
    }

    /** The value of an integer or float scalar as a decimal, read from its text; empty for infinity and not a number. */
    private static Optional<BigDecimal> decimal(ScalarNode scalar) {
        if (isInteger(scalar)) {
            return Optional.of(new BigDecimal(integerValue(scalar)));
        }
        if (isFloat(scalar)) {
            try {
                return Optional.of(new BigDecimal(scalar.getValue()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
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
