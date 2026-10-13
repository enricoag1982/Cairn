package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * How queries compare and order values (design D2): numbers by value across int, long and decimal, dates and instants in
 * time, strings by code point, and booleans for equality only (false before true where an order is needed, such as in the
 * order of the rows). Values of different kinds do not compare.
 */
final class Comparing {

    /** The kinds of values that compare with each other. */
    enum Category {
        NUMBER("a number (int, long or decimal)"),
        STRING("a string"),
        BOOLEAN("a boolean"),
        DATE("a date"),
        INSTANT("an instant");

        private final String phrase;

        Category(String phrase) {
            this.phrase = phrase;
        }

        String phrase() {
            return phrase;
        }
    }

    private static final Pattern DATE_FORM = Pattern.compile("^(?:[0-9]{4}|-[0-9]{4,9}|\\+[0-9]{5,9})-[0-9]{2}-[0-9]{2}$");
    private static final Pattern INSTANT_FORM = Pattern.compile("^[+-]?[0-9]{4,10}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}"
            + ":[0-9]{2}(\\.[0-9]{0,9})?([Zz]|[+-][0-9]{2}:[0-9]{2}(:[0-9]{2})?)$");

    private Comparing() {
    }

    static Category category(ValueType type) {
        return switch (type) {
            case INT, LONG, DECIMAL -> Category.NUMBER;
            case STRING -> Category.STRING;
            case BOOLEAN -> Category.BOOLEAN;
            case DATE -> Category.DATE;
            case INSTANT -> Category.INSTANT;
        };
    }

    /** Tells whether values of two value types can be compared with a comparison. */
    static boolean comparable(ValueType left, ValueType right, Comparison comparison) {
        Category category = category(left);
        return category == category(right) && !(comparison.needsOrder() && category == Category.BOOLEAN);
    }

    /**
     * Gives a literal the value type of the attribute it is compared with where it can: a string that spells a date, or
     * an instant in the form that transaction payloads use, becomes one for a date or an instant attribute. A number
     * needs no conversion, because numbers compare by value; anything else is returned as it is.
     *
     * <p>The forms are decided by patterns, as the document reader decides them, because what {@code java.time} accepts
     * differs between Java versions: a date such as {@code 2026-03-31}, and an instant with seconds, a fraction of up to
     * nine digits if need be, and {@code Z} or an offset {@code ±hh:mm}, such as {@code 2026-03-31T00:00:00Z}. This
     * engine does not depend on the module of the documents, so the patterns are repeated here; a test of that module
     * compares the two.
     */
    static Value coerce(Value literal, ValueType target) {
        if (literal instanceof Value.StringValue(String text)) {
            try {
                if (target == ValueType.DATE && DATE_FORM.matcher(text).matches()) {
                    return Value.of(LocalDate.parse(text));
                }
                if (target == ValueType.INSTANT && INSTANT_FORM.matcher(text).matches()) {
                    return Value.of(Instant.parse(text));
                }
            } catch (DateTimeException e) {
                return literal;
            }
        }
        return literal;
    }

    /** Tells whether {@code first comparison second} holds; values of different kinds do not compare. */
    static boolean holds(Value first, Comparison comparison, Value second) {
        if (category(first.valueType()) != category(second.valueType())) {
            return false;
        }
        return switch (comparison) {
            case EQ -> equal(first, second);
            case NE -> !equal(first, second);
            case LT -> order(first, second) < 0;
            case LE -> order(first, second) <= 0;
            case GT -> order(first, second) > 0;
            case GE -> order(first, second) >= 0;
        };
    }

    /** Tells whether two values of one kind are equal; numbers by value. */
    static boolean equal(Value first, Value second) {
        return switch (category(first.valueType())) {
            case NUMBER, DATE, INSTANT -> order(first, second) == 0;
            case STRING, BOOLEAN -> first.equals(second);
        };
    }

    /** Orders two values of one kind; across kinds, by the kind. */
    static int order(Value first, Value second) {
        Category category = category(first.valueType());
        if (category != category(second.valueType())) {
            return category.compareTo(category(second.valueType()));
        }
        return switch (category) {
            case NUMBER -> number(first).compareTo(number(second));
            case STRING -> codePoints(((Value.StringValue) first).value(), ((Value.StringValue) second).value());
            case BOOLEAN -> Boolean.compare(((Value.BooleanValue) first).value(), ((Value.BooleanValue) second).value());
            case DATE -> ((Value.DateValue) first).value().compareTo(((Value.DateValue) second).value());
            case INSTANT -> ((Value.InstantValue) first).value().compareTo(((Value.InstantValue) second).value());
        };
    }

    /** Orders two values that may be missing; a missing value comes first. */
    static int orderMissingFirst(Value first, Value second) {
        if (first == null || second == null) {
            return first == second ? 0 : first == null ? -1 : 1;
        }
        return order(first, second);
    }

    /** The value of a number as a decimal; the value must be a number. */
    static BigDecimal number(Value value) {
        return switch (value) {
            case Value.IntValue(int number) -> BigDecimal.valueOf(number);
            case Value.LongValue(long number) -> BigDecimal.valueOf(number);
            case Value.DecimalValue(BigDecimal number) -> number;
            default -> throw new IllegalArgumentException("Not a number: " + value);
        };
    }

    /** Compares strings by code point, not by UTF-16 code unit. */
    static int codePoints(String first, String second) {
        int i = 0;
        int j = 0;
        while (i < first.length() && j < second.length()) {
            int a = first.codePointAt(i);
            int b = second.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(first.length() - i, second.length() - j);
    }
}
