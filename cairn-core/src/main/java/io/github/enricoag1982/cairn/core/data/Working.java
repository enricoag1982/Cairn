package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;

/**
 * An entity or a relationship as a transaction changes it: its identity, the timeline it had when the transaction
 * started, and the timeline the writes have given it so far (design D5 of {@code add-entity-versions} and D2 of
 * {@code add-relationships}). A failed one stands for something that could not be created.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Working {

    final Id id;
    /**
     * What problems about it call it: its temporary ID if it was created with one, else its ID if the caller supplied
     * it, else {@code @write.<n>} for the write that creates it. An existing one is called by its ID.
     */
    final String path;
    /** The entity's type; null for a relationship and for a failed entity. */
    final EntityType entityType;
    /** The relationship's type; null for an entity and for a failed relationship. */
    final RelationshipType relationshipType;
    /** The ends of a relationship; null for an entity and for a failed relationship. */
    final EntityId source;
    final EntityId target;
    final boolean created;
    /** What the store holds of it; null if the transaction creates it. */
    final Held stored;
    final Timeline before;
    Timeline timeline;
    /**
     * A write targets it, creates it or cascades to it, as opposed to an entity that is only the end of a relationship.
     * Only what is written is validated and has its rules run.
     */
    boolean written;
    /** A write named an attribute that does not exist, so a required attribute may be missing under that name. */
    boolean namesUnresolved;
    /**
     * A write that would relate the entity failed, so a relationship that it needs may be missing for that reason.
     */
    boolean linksUnresolved;

    private Working(Id id, String path, EntityType entityType, RelationshipType relationshipType, EntityId source,
            EntityId target, boolean created, Held stored, Timeline before, Timeline timeline) {
        this.id = id;
        this.path = path;
        this.entityType = entityType;
        this.relationshipType = relationshipType;
        this.source = source;
        this.target = target;
        this.created = created;
        this.stored = stored;
        this.before = before;
        this.timeline = timeline;
    }

    /** An entity that the transaction creates. */
    static Working createdEntity(EntityId id, String path, EntityType type, Timeline timeline) {
        return new Working(id, path, type, null, null, null, true, null, Timeline.EMPTY, timeline);
    }

    /** A relationship that the transaction creates. */
    static Working createdRelationship(RelationshipId id, String path, RelationshipType type, EntityId source,
            EntityId target, Timeline timeline) {
        return new Working(id, path, null, type, source, target, true, null, Timeline.EMPTY, timeline);
    }

    /** An entity of the store; its type is null if the transaction's schema does not have it. */
    static Working existingEntity(Held entity, EntityType type) {
        Timeline latest = entity.timeline();
        return new Working(entity.id(), entity.id().value(), type, null, null, null, false, entity, latest, latest);
    }

    /** A relationship of the store; its type is null if the transaction's schema does not have it. */
    static Working existingRelationship(Held relationship, RelationshipType type) {
        Timeline latest = relationship.timeline();
        return new Working(relationship.id(), relationship.id().value(), null, type, relationship.source(),
                relationship.target(), false, relationship, latest, latest);
    }

    /** Stands for an entity or relationship that could not be created. */
    static Working failed(Id id, String path) {
        return new Working(id, path, null, null, null, null, true, null, Timeline.EMPTY, Timeline.EMPTY);
    }

    boolean failed() {
        return entityType == null && relationshipType == null;
    }

    boolean isRelationship() {
        return id instanceof RelationshipId;
    }

    /** The type of what it stands for; null if it failed. */
    SubjectType type() {
        if (entityType != null) {
            return SubjectType.of(entityType);
        }
        return relationshipType != null ? SubjectType.of(relationshipType) : null;
    }

    /** Whether it is called an {@code entity} or a {@code relationship}. */
    String kind() {
        return isRelationship() ? "relationship" : "entity";
    }
}
