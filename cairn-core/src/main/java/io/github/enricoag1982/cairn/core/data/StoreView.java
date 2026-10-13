package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A view of the in-memory store (design D4 of {@code add-entity-versions} and D2 of {@code add-relationships}): an
 * effective time, a basis, and the schema as it was at that basis. It takes for each entity and relationship the
 * timeline with the greatest basis at or below its own, and in it the segment that contains its effective time. The
 * store only appends under bases above the one a view was created with, so a view never waits for a writer and always
 * gives the same answer.
 */
final class StoreView extends NavigableView {

    private final Instant effectiveAt;
    private final Basis basis;
    private final SchemaIndex index;
    private final NavigableMap<EntityId, StoredEntity> entities;
    private final NavigableMap<RelationshipId, StoredRelationship> relationships;

    StoreView(Instant effectiveAt, Basis basis, SchemaIndex index, NavigableMap<EntityId, StoredEntity> entities,
            NavigableMap<RelationshipId, StoredRelationship> relationships) {
        this.effectiveAt = effectiveAt;
        this.basis = basis;
        this.index = index;
        this.entities = entities;
        this.relationships = relationships;
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

    @Override
    public Optional<RelationshipState> relationship(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        StoredRelationship relationship = relationships.get(id);
        if (relationship == null) {
            return Optional.empty();
        }
        return relationship.at(basis).at(effectiveAt).flatMap(segment -> state(relationship, segment));
    }

    @Override
    public List<RelationshipState> relationshipTimeline(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        StoredRelationship relationship = relationships.get(id);
        if (relationship == null) {
            return List.of();
        }
        List<RelationshipState> states = new ArrayList<>();
        for (Segment segment : relationship.at(basis).segments()) {
            state(relationship, segment).ifPresent(states::add);
        }
        return List.copyOf(states);
    }

    @Override
    SchemaIndex index() {
        return index;
    }

    @Override
    String schemaName() {
        return "The schema at basis " + basis.value();
    }

    @Override
    List<RelationshipState> relationshipVersions(Set<TypeId> types, EntityId end) {
        List<RelationshipState> states = new ArrayList<>();
        for (StoredRelationship relationship : relationships.values()) {
            if (types.contains(relationship.type())
                    && (relationship.source().equals(end) || relationship.target().equals(end))) {
                for (Segment segment : relationship.at(basis).segments()) {
                    state(relationship, segment).ifPresent(states::add);
                }
            }
        }
        return states;
    }

    private Optional<EntityState> state(StoredEntity entity, Segment segment) {
        return EntityStates.of(index, entity.id(), entity.type(), segment);
    }

    private Optional<RelationshipState> state(StoredRelationship relationship, Segment segment) {
        return RelationshipStates.of(index, relationship.id(), relationship.type(), relationship.source(),
                relationship.target(), segment);
    }
}
