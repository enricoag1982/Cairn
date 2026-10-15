package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One version of an entity inside a {@link Timeline} (design D4): an effective period with the values that hold in it,
 * keyed by the identifier of their attribute, and the transaction that recorded it. Immutable.
 *
 * <p>An open end is the instant {@link #OPEN}, which no effective time reaches, so periods compare without special
 * cases.
 *
 * @param from          the first instant of the period
 * @param end           the first instant after the period, or {@link #OPEN}
 * @param values        the values, keyed by attribute identifier; attributes without a value are absent
 * @param recordedAt    the transaction time of the transaction that recorded these values
 * @param recordedBasis the basis of that transaction
 */
record Segment(Instant from, Instant end, Map<AttributeId, Value> values, Instant recordedAt, Basis recordedBasis) {

    /** The end of a period that has none. */
    static final Instant OPEN = Instant.MAX;

    Segment {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(from)) {
            throw new IllegalArgumentException("A segment must end after it starts: " + from + " to " + end + ".");
        }
        values = Map.copyOf(values);
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
    }

    /** The end instant of a period: its end, or {@link #OPEN}. */
    static Instant endOf(EffectivePeriod period) {
        return period.until().orElse(OPEN);
    }

    /** Describes a stretch of effective time, such as {@code from 2026-01-01T00:00:00Z until further notice}. */
    static String describe(Instant from, Instant end) {
        return "from " + from + (end.equals(OPEN) ? " until further notice" : " until " + end);
    }

    /** The period of this segment, as the API reports it. */
    EffectivePeriod period() {
        return end.equals(OPEN) ? EffectivePeriod.from(from) : EffectivePeriod.between(from, end);
    }

    boolean contains(Instant instant) {
        return !instant.isBefore(from) && instant.isBefore(end);
    }

    /** The same version restricted to another period. */
    Segment withBounds(Instant newFrom, Instant newEnd) {
        return new Segment(newFrom, newEnd, values, recordedAt, recordedBasis);
    }

    /** Tells whether the same transaction recorded two segments: they have the same transaction time and basis. */
    boolean sameRecording(Segment other) {
        return recordedAt.equals(other.recordedAt) && recordedBasis.equals(other.recordedBasis);
    }
}
