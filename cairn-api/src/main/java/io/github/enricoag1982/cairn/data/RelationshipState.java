package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One version of a relationship, as a {@link View} reports it (ADR-0010, capability {@code relationships}): the
 * relationship's ID and type, the entities at its two ends, the values of its attributes, the effective period of the
 * version, and the transaction that recorded it.
 *
 * <p>The type and the ends of a relationship never change, so every version of one relationship has the same type,
 * source and target. For a symmetric relationship type, the source is the end that the relationship was created with.
 *
 * @param id               the relationship's ID
 * @param relationshipType the name of the relationship's type in the view's schema
 * @param source           the ID of the entity at the source end
 * @param target           the ID of the entity at the target end
 * @param values           the values, keyed by the attribute names of the view's schema and in the order of the
 *                         attributes in that schema; each value has the attribute's value type in that schema, which
 *                         may differ from the type it was written in; attributes without a value are absent
 * @param period           the effective period of this version
 * @param recordedAt       the transaction time of the transaction that recorded this version
 * @param recordedBasis    the basis of the transaction that recorded this version
 */
public record RelationshipState(
        RelationshipId id,
        String relationshipType,
        EntityId source,
        EntityId target,
        Map<String, Value> values,
        EffectivePeriod period,
        Instant recordedAt,
        Basis recordedBasis) {

    /**
     * Rejects null components and copies the values, keeping their order.
     */
    public RelationshipState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(relationshipType, "relationshipType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        values = Copies.map(values, "values");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
    }
}
