package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/** Comparison of values and range bounds, and how to show them. */
final class Values {

    private Values() {
    }

    /**
     * Tells whether a value can bound an attribute of a value type, or be an example of it; narrower numbers fit
     * wider ones.
     */
    static boolean fits(Value value, ValueType type) {
        return switch (value) {
            case Value.IntValue ignored -> type == ValueType.INT || type == ValueType.LONG || type == ValueType.DECIMAL;
            case Value.LongValue ignored -> type == ValueType.LONG || type == ValueType.DECIMAL;
            case Value.DecimalValue ignored -> type == ValueType.DECIMAL;
            case Value.StringValue ignored -> type == ValueType.STRING;
            case Value.BooleanValue ignored -> type == ValueType.BOOLEAN;
            case Value.DateValue ignored -> type == ValueType.DATE;
            case Value.InstantValue ignored -> type == ValueType.INSTANT;
        };
    }

    /** Compares two values, or returns empty when they are of incomparable kinds. */
    static OptionalInt compare(Value first, Value second) {
        Optional<BigDecimal> a = number(first);
        Optional<BigDecimal> b = number(second);
        if (a.isPresent() && b.isPresent()) {
            return OptionalInt.of(a.get().compareTo(b.get()));
        }
        if (first instanceof Value.DateValue(var x) && second instanceof Value.DateValue(var y)) {
            return OptionalInt.of(x.compareTo(y));
        }
        if (first instanceof Value.InstantValue(var x) && second instanceof Value.InstantValue(var y)) {
            return OptionalInt.of(x.compareTo(y));
        }
        return OptionalInt.empty();
    }

    /** Tells whether a value is inside a range. Bounds that cannot be compared with the value are not held against it. */
    static boolean within(Value value, Range range) {
        return aboveLower(value, range.lower()) && belowUpper(value, range.upper());
    }

    private static boolean aboveLower(Value value, Optional<Bound> lower) {
        if (lower.isEmpty()) {
            return true;
        }
        OptionalInt order = compare(value, lower.get().value());
        return order.isEmpty() || order.getAsInt() > 0 || order.getAsInt() == 0 && lower.get().inclusive();
    }

    private static boolean belowUpper(Value value, Optional<Bound> upper) {
        if (upper.isEmpty()) {
            return true;
        }
        OptionalInt order = compare(value, upper.get().value());
        return order.isEmpty() || order.getAsInt() < 0 || order.getAsInt() == 0 && upper.get().inclusive();
    }

    /** A value as plain text. */
    static String describe(Value constant) {
        return switch (constant) {
            case Value.StringValue(String value) -> value;
            case Value.BooleanValue(boolean value) -> String.valueOf(value);
            case Value.IntValue(int value) -> String.valueOf(value);
            case Value.LongValue(long value) -> String.valueOf(value);
            case Value.DecimalValue(BigDecimal value) -> value.toPlainString();
            case Value.DateValue(var value) -> value.toString();
            case Value.InstantValue(var value) -> value.toString();
        };
    }

    /** A range as a condition on a value, such as {@code > 0 and <= 100}. */
    static String describe(Range range) {
        List<String> conditions = new ArrayList<>();
        range.lower().ifPresent(bound -> conditions.add((bound.inclusive() ? ">= " : "> ") + describe(bound.value())));
        range.upper().ifPresent(bound -> conditions.add((bound.inclusive() ? "<= " : "< ") + describe(bound.value())));
        return String.join(" and ", conditions);
    }

    /** The name of a value type as it is written in schema definitions, in lower case. */
    static String typeName(ValueType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /** Tells whether no value fits a range. A range with incomparable bounds is not reported as empty. */
    static boolean isEmpty(Range range) {
        if (range.lower().isEmpty() || range.upper().isEmpty()) {
            return false;
        }
        Bound lower = range.lower().get();
        Bound upper = range.upper().get();
        OptionalInt order = compare(lower.value(), upper.value());
        if (order.isEmpty()) {
            return false;
        }
        return order.getAsInt() > 0 || order.getAsInt() == 0 && !(lower.inclusive() && upper.inclusive());
    }

    /** Tells whether every value inside {@code inner} is also inside {@code outer}. */
    static boolean contains(Optional<Range> outer, Optional<Range> inner) {
        if (outer.isEmpty()) {
            return true;
        }
        if (inner.isEmpty()) {
            return outer.get().lower().isEmpty() && outer.get().upper().isEmpty();
        }
        return lowerContains(outer.get().lower(), inner.get().lower())
                && upperContains(outer.get().upper(), inner.get().upper());
    }

    private static boolean lowerContains(Optional<Bound> outer, Optional<Bound> inner) {
        if (outer.isEmpty()) {
            return true;
        }
        if (inner.isEmpty()) {
            return false;
        }
        OptionalInt order = compare(outer.get().value(), inner.get().value());
        if (order.isEmpty()) {
            return false;
        }
        return order.getAsInt() < 0 || order.getAsInt() == 0 && (outer.get().inclusive() || !inner.get().inclusive());
    }

    private static boolean upperContains(Optional<Bound> outer, Optional<Bound> inner) {
        if (outer.isEmpty()) {
            return true;
        }
        if (inner.isEmpty()) {
            return false;
        }
        OptionalInt order = compare(outer.get().value(), inner.get().value());
        if (order.isEmpty()) {
            return false;
        }
        return order.getAsInt() > 0 || order.getAsInt() == 0 && (outer.get().inclusive() || !inner.get().inclusive());
    }

    private static Optional<BigDecimal> number(Value constant) {
        return switch (constant) {
            case Value.IntValue(int value) -> Optional.of(BigDecimal.valueOf(value));
            case Value.LongValue(long value) -> Optional.of(BigDecimal.valueOf(value));
            case Value.DecimalValue(BigDecimal value) -> Optional.of(value);
            default -> Optional.empty();
        };
    }
}
