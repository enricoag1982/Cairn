package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * One end of a {@link Range}: a value that is either included or excluded (capability {@code type-model}).
 *
 * @param value     the bounding value
 * @param inclusive {@code true} if the value itself is inside the range
 */
public record Bound(Value value, boolean inclusive) {

    /**
     * Rejects a null value.
     */
    public Bound {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a bound that includes its value.
     *
     * @param value the bounding value
     * @return the bound
     */
    public static Bound inclusive(Value value) {
        return new Bound(value, true);
    }

    /**
     * Creates a bound that excludes its value.
     *
     * @param value the bounding value
     * @return the bound
     */
    public static Bound exclusive(Value value) {
        return new Bound(value, false);
    }
}
