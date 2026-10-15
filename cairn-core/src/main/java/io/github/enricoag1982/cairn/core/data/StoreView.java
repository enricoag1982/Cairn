package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A view of the in-memory store (design D4 of {@code add-entity-versions} and D2 of {@code add-relationships}): an
 * effective time, a basis, and the schema as it was at that basis. It takes for each entity and relationship the
 * timeline that the {@link Ledger} gives at its basis, and in it the segment that contains its effective time. A
 * commit only writes under bases above the one a view was created with, so a view never waits for a writer and always
 * gives the same answer. Listings, navigation and unique keys read the ledger's indexes and then each candidate at the
 * view's basis.
 */
final class StoreView extends NavigableView {

    private final Instant effectiveAt;
    private final Basis basis;
    private final SchemaIndex index;
    private final Ledger ledger;

    StoreView(Instant effectiveAt, Basis basis, SchemaIndex index, Ledger ledger) {
        this.effectiveAt = effectiveAt;
        this.basis = basis;
        this.index = index;
        this.ledger = ledger;
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
        return ledger.entity(id, basis).flatMap(entity -> entity.timeline().at(effectiveAt)
                .flatMap(segment -> entityState(entity, segment)));
    }

    @Override
    public List<EntityState> entities(String entityType) {
        Objects.requireNonNull(entityType, "entityType");
        EntityType type = index.schema().entityType(entityType).orElseThrow(() -> new IllegalArgumentException(
                "The schema at basis " + basis.value() + " has no entity type '" + entityType + "'."));
        List<EntityState> states = new ArrayList<>();
        for (Held entity : ledger.entities(index.typeAndSubtypes(type), basis)) {
            entity.timeline().at(effectiveAt).flatMap(segment -> entityState(entity, segment)).ifPresent(states::add);
        }
        return List.copyOf(states);
    }

    /** Finds the entity by the index of unique values: its candidates are the entities that ever had the value. */
    @Override
    Optional<EntityState> findByKey(UniqueKeys.Valid valid) {
        Set<TypeId> types = index.typeAndSubtypes(valid.type());
        for (Held entity : ledger.uniqueHolders(valid.attribute().id(), valid.value(), false, basis)) {
            if (!types.contains(entity.type())) {
                continue;
            }
            Optional<EntityState> state = entity.timeline().at(effectiveAt).flatMap(segment -> entityState(entity, segment));
            if (state.isPresent()) {
                Value held = state.get().values().get(valid.attribute().name());
                if (held != null && Conversions.same(held, valid.value())) {
                    return state;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        Objects.requireNonNull(id, "id");
        List<EntityState> states = new ArrayList<>();
        ledger.entity(id, basis).ifPresent(entity -> {
            for (Segment segment : entity.timeline().segments()) {
                entityState(entity, segment).ifPresent(states::add);
            }
        });
        return List.copyOf(states);
    }

    @Override
    public Optional<RelationshipState> relationship(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        return ledger.relationship(id, basis).flatMap(relationship -> relationship.timeline().at(effectiveAt)
                .flatMap(segment -> relationshipState(relationship, segment)));
    }

    @Override
    public List<RelationshipState> relationshipTimeline(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        List<RelationshipState> states = new ArrayList<>();
        ledger.relationship(id, basis).ifPresent(relationship -> {
            for (Segment segment : relationship.timeline().segments()) {
                relationshipState(relationship, segment).ifPresent(states::add);
            }
        });
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
    List<RelationshipState> relationshipVersions(Set<TypeId> types, EntityId end, Ledger.Direction direction) {
        List<RelationshipState> states = new ArrayList<>();
        for (Held relationship : ledger.relationships(end, types, direction, basis)) {
            for (Segment segment : relationship.timeline().segments()) {
                relationshipState(relationship, segment).ifPresent(states::add);
            }
        }
        return states;
    }

    private Optional<EntityState> entityState(Held entity, Segment segment) {
        return EntityStates.of(index, entity.entityId(), entity.type(), segment);
    }

    private Optional<RelationshipState> relationshipState(Held relationship, Segment segment) {
        return RelationshipStates.of(index, relationship.relationshipId(), relationship.type(), relationship.source(),
                relationship.target(), segment);
    }
}
