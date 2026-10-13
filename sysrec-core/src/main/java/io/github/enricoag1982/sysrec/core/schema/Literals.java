package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.Bound;
import io.github.enricoag1982.sysrec.schema.Literal;
import io.github.enricoag1982.sysrec.schema.Range;
import io.github.enricoag1982.sysrec.schema.ValueType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalInt;

/** Comparison of range literals and bounds. */
final class Literals {

    private Literals() {
    }

    /** Tells whether a literal can bound an attribute of a value type; narrower numbers fit wider ones. */
    static boolean fits(Literal literal, ValueType type) {
        return switch (literal) {
            case Literal.IntLiteral ignored -> type == ValueType.INT || type == ValueType.LONG || type == ValueType.DECIMAL;
            case Literal.LongLiteral ignored -> type == ValueType.LONG || type == ValueType.DECIMAL;
            case Literal.DecimalLiteral ignored -> type == ValueType.DECIMAL;
            case Literal.DateLiteral ignored -> type == ValueType.DATE;
            case Literal.InstantLiteral ignored -> type == ValueType.INSTANT;
        };
    }

    /** Compares two literals, or returns empty when they are of incomparable kinds. */
    static OptionalInt compare(Literal first, Literal second) {
        Optional<BigDecimal> a = number(first);
        Optional<BigDecimal> b = number(second);
        if (a.isPresent() && b.isPresent()) {
            return OptionalInt.of(a.get().compareTo(b.get()));
        }
        if (first instanceof Literal.DateLiteral(var x) && second instanceof Literal.DateLiteral(var y)) {
            return OptionalInt.of(x.compareTo(y));
        }
        if (first instanceof Literal.InstantLiteral(var x) && second instanceof Literal.InstantLiteral(var y)) {
            return OptionalInt.of(x.compareTo(y));
        }
        return OptionalInt.empty();
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

    private static Optional<BigDecimal> number(Literal literal) {
        return switch (literal) {
            case Literal.IntLiteral(int value) -> Optional.of(BigDecimal.valueOf(value));
            case Literal.LongLiteral(long value) -> Optional.of(BigDecimal.valueOf(value));
            case Literal.DecimalLiteral(BigDecimal value) -> Optional.of(value);
            default -> Optional.empty();
        };
    }
}
