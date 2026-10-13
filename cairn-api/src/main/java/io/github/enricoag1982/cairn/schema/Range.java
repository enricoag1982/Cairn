package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * The interval that the values of an ordered attribute must lie in: an optional lower and an optional upper bound
 * (capability {@code type-model}).
 *
 * @param lower the lower bound, if any
 * @param upper the upper bound, if any
 */
public record Range(Optional<Bound> lower, Optional<Bound> upper) {

    /**
     * Rejects null components.
     */
    public Range {
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
    }

    /**
     * Creates a range with both bounds.
     *
     * @param lower the lower bound
     * @param upper the upper bound
     * @return the range
     */
    public static Range between(Bound lower, Bound upper) {
        return new Range(Optional.of(lower), Optional.of(upper));
    }

    /**
     * Creates a range with only a lower bound.
     *
     * @param lower the lower bound
     * @return the range
     */
    public static Range atLeast(Bound lower) {
        return new Range(Optional.of(lower), Optional.empty());
    }

    /**
     * Creates a range with only an upper bound.
     *
     * @param upper the upper bound
     * @return the range
     */
    public static Range atMost(Bound upper) {
        return new Range(Optional.empty(), Optional.of(upper));
    }
}
