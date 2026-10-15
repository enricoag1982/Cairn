package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Turns a {@link Segment} into the {@link EntityState} that a view reports (design D4 of {@code add-entity-versions}). */
final class EntityStates {

    private EntityStates() {
    }

    /**
     * The state of an entity in one of its segments: values under the attribute names of the schema, in the order of
     * its attributes, each in the attribute's value type in that schema.
     *
     * @param index   the schema to report in
     * @param id      the entity's ID
     * @param type    the entity's type
     * @param segment the segment
     * @return the state; empty if the schema has no such entity type
     */
    static Optional<EntityState> of(SchemaIndex index, EntityId id, TypeId type, Segment segment) {
        Optional<EntityType> entityType = index.entityType(type);
        if (entityType.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Value> values = new LinkedHashMap<>();
        for (Attribute attribute : entityType.get().attributes()) {
            Value value = segment.values().get(attribute.id());
            if (value != null) {
                values.put(attribute.name(), Conversions.convert(value, attribute.valueType()));
            }
        }
        return Optional.of(new EntityState(id, entityType.get().name(), values, segment.period(), segment.recordedAt(),
                segment.recordedBasis()));
    }
}
