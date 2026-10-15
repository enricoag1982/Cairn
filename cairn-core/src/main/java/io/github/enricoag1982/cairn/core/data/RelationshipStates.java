package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a {@link Segment} into the {@link RelationshipState} that a view reports (design D1 and D2 of
 * {@code add-relationships}), as {@link EntityStates} does for entities.
 */
final class RelationshipStates {

    private RelationshipStates() {
    }

    /**
     * The state of a relationship in one of its segments: values under the attribute names of the schema, in the order
     * of its attributes, each in the attribute's value type in that schema.
     *
     * @param index   the schema to report in
     * @param id      the relationship's ID
     * @param type    the relationship's type
     * @param source  the entity at the relationship's source end
     * @param target  the entity at the relationship's target end
     * @param segment the segment
     * @return the state; empty if the schema has no such relationship type
     */
    static Optional<RelationshipState> of(SchemaIndex index, RelationshipId id, TypeId type, EntityId source,
            EntityId target, Segment segment) {
        Optional<RelationshipType> relationshipType = index.relationshipType(type);
        if (relationshipType.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Value> values = new LinkedHashMap<>();
        for (Attribute attribute : relationshipType.get().attributes()) {
            Value value = segment.values().get(attribute.id());
            if (value != null) {
                values.put(attribute.name(), Conversions.convert(value, attribute.valueType()));
            }
        }
        return Optional.of(new RelationshipState(id, relationshipType.get().name(), source, target, values,
                segment.period(), segment.recordedAt(), segment.recordedBasis()));
    }
}
