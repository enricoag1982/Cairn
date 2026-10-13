package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A view of the in-memory store (design D4): an effective time, a basis, and the schema as it was at that basis. It
 * takes for each entity the timeline with the greatest basis at or below its own, and in it the segment that contains
 * its effective time. The store only appends under bases above the one a view was created with, so a view never
 * waits for a writer and always gives the same answer.
 */
final class StoreView implements View {

    private final Instant effectiveAt;
    private final Basis basis;
    private final SchemaIndex index;
    private final NavigableMap<EntityId, StoredEntity> entities;

    StoreView(Instant effectiveAt, Basis basis, SchemaIndex index, NavigableMap<EntityId, StoredEntity> entities) {
        this.effectiveAt = effectiveAt;
        this.basis = basis;
        this.index = index;
        this.entities = entities;
    }

    @Override
    public Instant effectiveAt() {
        return effectiveAt;
    }

    @Override
    public Basis basis() {
        return basis;
    }

    @Override
    public Schema schema() {
        return index.schema();
    }

    @Override
    public Optional<EntityState> entity(EntityId id) {
        Objects.requireNonNull(id, "id");
        StoredEntity entity = entities.get(id);
        if (entity == null) {
            return Optional.empty();
        }
        return entity.at(basis).at(effectiveAt).flatMap(segment -> state(entity, segment));
    }

    @Override
    public List<EntityState> entities(String entityType) {
        Objects.requireNonNull(entityType, "entityType");
        EntityType type = index.schema().entityType(entityType).orElseThrow(() -> new IllegalArgumentException(
                "The schema at basis " + basis.value() + " has no entity type '" + entityType + "'."));
        Set<TypeId> types = index.typeAndSubtypes(type);
        List<EntityState> states = new ArrayList<>();
        for (StoredEntity entity : entities.values()) {
            if (types.contains(entity.type())) {
                entity.at(basis).at(effectiveAt).flatMap(segment -> state(entity, segment)).ifPresent(states::add);
            }
        }
        return List.copyOf(states);
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        Objects.requireNonNull(id, "id");
        StoredEntity entity = entities.get(id);
        if (entity == null) {
            return List.of();
        }
        List<EntityState> states = new ArrayList<>();
        for (Segment segment : entity.at(basis).segments()) {
            state(entity, segment).ifPresent(states::add);
        }
        return List.copyOf(states);
    }

    /**
     * The state of an entity in one of its segments: values under the attribute names of the view's schema, in the
     * order of its attributes, each in the attribute's value type in that schema.
     */
    private Optional<EntityState> state(StoredEntity entity, Segment segment) {
        Optional<EntityType> type = index.entityType(entity.type());
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Value> values = new LinkedHashMap<>();
        for (Attribute attribute : type.get().attributes()) {
            Value value = segment.values().get(attribute.id());
            if (value != null) {
                values.put(attribute.name(), Conversions.convert(value, attribute.valueType()));
            }
        }
        return Optional.of(new EntityState(entity.id(), type.get().name(), values, segment.period(),
                segment.recordedAt(), segment.recordedBasis()));
    }
}
