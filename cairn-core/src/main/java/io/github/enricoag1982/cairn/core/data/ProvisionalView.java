package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The view that rules read through (design D3 of {@code add-entity-rules}): the state a transaction would produce. It
 * takes the latest committed timelines of the store, of entities and of relationships, and overlays the working
 * timelines of the transaction, with the schema the transaction would produce. Its basis is the prospective basis of
 * the transaction, and the working segments carry it as their recorded basis.
 *
 * <p>The view is read-only and valid only while the rules of the transaction run: in a dry run, or if the commit is
 * rejected, the prospective basis later names another commit. The transaction holds the store's writer lock, so the
 * committed timelines do not change underneath it. All views of a transaction share one {@link Source}, which is
 * created once; {@link Source#at(Instant)} makes the view at an effective time.
 *
 * <p>A view made for the context of a rule records what the rule reads into the rule's {@link Reads} (design D1 of
 * {@code add-rule-rechecks}). The recording is in the view's own reads, below the methods of {@code View} that
 * navigation, traversals and queries are built on, so that every way a rule reads is covered; a view made for any other
 * purpose records nothing.
 */
final class ProvisionalView extends NavigableView {

    /**
     * An entity as the transaction would leave it.
     *
     * @param type     the entity's type
     * @param timeline its timeline after the transaction
     */
    record Overlaid(TypeId type, Timeline timeline) {
    }

    /**
     * A relationship as the transaction would leave it.
     *
     * @param type     the relationship's type
     * @param source   the entity at its source end
     * @param target   the entity at its target end
     * @param timeline its timeline after the transaction
     */
    record OverlaidRelationship(TypeId type, EntityId source, EntityId target, Timeline timeline) {
    }

    /**
     * What the views of one transaction share.
     *
     * @param basis                the basis the transaction would get
     * @param index                the schema the transaction would produce
     * @param stored               the entities of the store, of which the latest timelines are the committed state
     * @param overlay              the entities the transaction creates or changes, with the timelines it would give
     *                             them
     * @param storedRelationships  the relationships of the store, of which the latest timelines are the committed state
     * @param relationshipOverlay  the relationships the transaction creates or changes, with the timelines it would
     *                             give them
     */
    record Source(Basis basis, SchemaIndex index, Map<EntityId, StoredEntity> stored, Map<EntityId, Overlaid> overlay,
            Map<RelationshipId, StoredRelationship> storedRelationships,
            Map<RelationshipId, OverlaidRelationship> relationshipOverlay) {

        /** The view of the transaction's state at an effective time. It records no reads. */
        ProvisionalView at(Instant effectiveAt) {
            return at(effectiveAt, null);
        }

        /**
         * The view of the transaction's state at an effective time, which records what is read through it.
         *
         * @param effectiveAt the effective time
         * @param reads       where the reads go; null to record none
         */
        ProvisionalView at(Instant effectiveAt, Reads reads) {
            return new ProvisionalView(Objects.requireNonNull(effectiveAt, "effectiveAt")
                    .truncatedTo(ChronoUnit.MICROS), this, reads);
        }

        private Optional<Overlaid> find(EntityId id) {
            Overlaid overlaid = overlay.get(id);
            if (overlaid != null) {
                return Optional.of(overlaid);
            }
            StoredEntity entity = stored.get(id);
            return entity == null ? Optional.empty() : Optional.of(new Overlaid(entity.type(), entity.latest()));
        }

        private Optional<OverlaidRelationship> findRelationship(RelationshipId id) {
            OverlaidRelationship overlaid = relationshipOverlay.get(id);
            if (overlaid != null) {
                return Optional.of(overlaid);
            }
            return Optional.ofNullable(storedRelationships.get(id)).map(ProvisionalView::committed);
        }
    }

    private final Instant effectiveAt;
    private final Source source;
    /** Where the reads of the rule go; null for a view that records none. */
    private final Reads reads;

    private ProvisionalView(Instant effectiveAt, Source source, Reads reads) {
        this.effectiveAt = effectiveAt;
        this.source = source;
        this.reads = reads;
    }

    /** Records a read of a key at the view's effective time. */
    private void read(ReadKey key) {
        if (reads != null) {
            reads.at(key, effectiveAt);
        }
    }

    /** Records a read of a key across effective time. */
    private void readAcrossTime(ReadKey key) {
        if (reads != null) {
            reads.acrossTime(key);
        }
    }

    @Override
    public Instant effectiveAt() {
        return effectiveAt;
    }

    @Override
    public Basis basis() {
        return source.basis();
    }

    @Override
    public Schema schema() {
        return source.index().schema();
    }

    @Override
    public Optional<EntityState> entity(EntityId id) {
        Objects.requireNonNull(id, "id");
        read(new ReadKey.Entity(id));
        return source.find(id).flatMap(entity -> state(id, entity, entity.timeline().at(effectiveAt)));
    }

    @Override
    public List<EntityState> entities(String entityType) {
        Objects.requireNonNull(entityType, "entityType");
        EntityType type = source.index().schema().entityType(entityType).orElseThrow(() -> new IllegalArgumentException(
                "The schema of the transaction has no entity type '" + entityType + "'."));
        read(new ReadKey.OfType(type.id()));
        Set<TypeId> types = source.index().typeAndSubtypes(type);
        Map<EntityId, Overlaid> matching = new TreeMap<>();
        for (StoredEntity entity : source.stored().values()) {
            if (types.contains(entity.type()) && !source.overlay().containsKey(entity.id())) {
                matching.put(entity.id(), new Overlaid(entity.type(), entity.latest()));
            }
        }
        source.overlay().forEach((id, entity) -> {
            if (types.contains(entity.type())) {
                matching.put(id, entity);
            }
        });
        List<EntityState> states = new ArrayList<>();
        matching.forEach((id, entity) -> state(id, entity, entity.timeline().at(effectiveAt)).ifPresent(found -> {
            read(new ReadKey.Entity(id));
            states.add(found);
        }));
        return List.copyOf(states);
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        Objects.requireNonNull(id, "id");
        readAcrossTime(new ReadKey.Entity(id));
        List<EntityState> states = new ArrayList<>();
        source.find(id).ifPresent(entity -> {
            for (Segment segment : entity.timeline().segments()) {
                state(id, entity, Optional.of(segment)).ifPresent(states::add);
            }
        });
        return List.copyOf(states);
    }

    private Optional<EntityState> state(EntityId id, Overlaid entity, Optional<Segment> segment) {
        return segment.flatMap(found -> EntityStates.of(source.index(), id, entity.type(), found));
    }

    @Override
    public Optional<RelationshipState> relationship(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        read(new ReadKey.Relationship(id));
        return source.findRelationship(id)
                .flatMap(relationship -> relationshipState(id, relationship,
                        relationship.timeline().at(effectiveAt)));
    }

    @Override
    public List<RelationshipState> relationshipTimeline(RelationshipId id) {
        Objects.requireNonNull(id, "id");
        readAcrossTime(new ReadKey.Relationship(id));
        List<RelationshipState> states = new ArrayList<>();
        source.findRelationship(id).ifPresent(relationship -> {
            for (Segment segment : relationship.timeline().segments()) {
                relationshipState(id, relationship, Optional.of(segment)).ifPresent(states::add);
            }
        });
        return List.copyOf(states);
    }

    @Override
    void navigating(EntityId from, RelationshipType type, boolean inverse, boolean acrossTime) {
        if (reads == null) {
            return;
        }
        // A symmetric type leads from both ends by either name, so a read of one direction reads both.
        for (boolean forward : type.symmetric() ? new boolean[] {true, false} : new boolean[] {!inverse}) {
            ReadKey key = new ReadKey.Navigation(from, type.id(), forward);
            if (acrossTime) {
                reads.acrossTime(key);
            } else {
                reads.at(key, effectiveAt);
            }
        }
    }

    @Override
    void navigated(RelationshipState relationship, boolean acrossTime) {
        ReadKey key = new ReadKey.Relationship(relationship.id());
        if (acrossTime) {
            readAcrossTime(key);
        } else {
            read(key);
        }
    }

    @Override
    SchemaIndex index() {
        return source.index();
    }

    @Override
    String schemaName() {
        return "The schema of the transaction";
    }

    @Override
    List<RelationshipState> relationshipVersions(Set<TypeId> types, EntityId end) {
        Map<RelationshipId, OverlaidRelationship> matching = new TreeMap<>();
        for (StoredRelationship relationship : source.storedRelationships().values()) {
            if (!source.relationshipOverlay().containsKey(relationship.id())) {
                matching.put(relationship.id(), committed(relationship));
            }
        }
        matching.putAll(source.relationshipOverlay());
        List<RelationshipState> states = new ArrayList<>();
        matching.forEach((id, relationship) -> {
            if (types.contains(relationship.type())
                    && (relationship.source().equals(end) || relationship.target().equals(end))) {
                for (Segment segment : relationship.timeline().segments()) {
                    relationshipState(id, relationship, Optional.of(segment)).ifPresent(states::add);
                }
            }
        });
        return states;
    }

    private static OverlaidRelationship committed(StoredRelationship relationship) {
        return new OverlaidRelationship(relationship.type(), relationship.source(), relationship.target(),
                relationship.latest());
    }

    private Optional<RelationshipState> relationshipState(RelationshipId id, OverlaidRelationship relationship,
            Optional<Segment> segment) {
        return segment.flatMap(found -> RelationshipStates.of(source.index(), id, relationship.type(),
                relationship.source(), relationship.target(), found));
    }
}
