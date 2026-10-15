package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One version of an entity or relationship as the log records it: an effective period, the values that hold in it keyed
 * by attribute identifier, and the transaction that recorded them.
 *
 * @param from          the first instant of the period
 * @param end           the first instant after the period, or {@link Instant#MAX} for a period without an end
 * @param values        the values, keyed by attribute identifier
 * @param recordedAt    the transaction time of the transaction that recorded the values
 * @param recordedBasis the basis of that transaction
 */
public record SegmentData(
        Instant from,
        Instant end,
        Map<AttributeId, Value> values,
        Instant recordedAt,
        Basis recordedBasis) {

    /**
     * Rejects null components and copies the values.
     */
    public SegmentData {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(end, "end");
        values = Map.copyOf(values);
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
    }
}
