package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * @param withheld      the names of the attributes whose values a {@linkplain View#restrictedTo view restricted to a
 *                      clearance} withheld, in the order of the attributes in the view's schema, whether the version
 *                      had a value or not (capability {@code attribute-access}, ADR-0036); empty for a view that is not
 *                      restricted, and for a clearance that covers every attribute of the type
 */
public record EntityState(
        EntityId id,
        String entityType,
        Map<String, Value> values,
        EffectivePeriod period,
        Instant recordedAt,
        Basis recordedBasis,
        Set<String> withheld) {

    /**
     * Rejects null components and copies the values and the names of withheld attributes, keeping their order.
     */
    public EntityState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entityType, "entityType");
        values = Copies.map(values, "values");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
        withheld = Copies.set(withheld, "withheld");
    }

    /**
     * Creates the state of a version that withheld nothing.
     *
     * @param id            the entity's ID
     * @param entityType    the name of the entity's concrete type in the view's schema
     * @param values        the values, keyed by the attribute names of the view's schema
     * @param period        the effective period of this version
     * @param recordedAt    the transaction time of the transaction that recorded this version
     * @param recordedBasis the basis of the transaction that recorded this version
     */
    public EntityState(EntityId id, String entityType, Map<String, Value> values, EffectivePeriod period,
            Instant recordedAt, Basis recordedBasis) {
        this(id, entityType, values, period, recordedAt, recordedBasis, Set.of());
    }
}
