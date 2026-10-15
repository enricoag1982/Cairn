package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One version of an entity, as a {@link View} reports it (ADR-0009, capability {@code views}): the entity's ID and
 * type, the values of its attributes, the effective period of the version, and the transaction that recorded it.
 *
 * @param id            the entity's ID
 * @param entityType    the name of the entity's concrete type in the view's schema
 * @param values        the values, keyed by the attribute names of the view's schema and in the order of the attributes
 *                      in that schema; each value has the attribute's value type in that schema, which may differ
 *                      from the type it was written in; attributes without a value are absent
 * @param period        the effective period of this version
 * @param recordedAt    the transaction time of the transaction that recorded this version
 * @param recordedBasis the basis of the transaction that recorded this version
 */
public record EntityState(
        EntityId id,
        String entityType,
        Map<String, Value> values,
        EffectivePeriod period,
        Instant recordedAt,
        Basis recordedBasis) {

    /**
     * Rejects null components and copies the values, keeping their order.
     */
    public EntityState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entityType, "entityType");
        values = Copies.map(values, "values");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
    }
}
