package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The entities and relationships that a transaction touches, with what it needs to find them (design D5 of
 * {@code add-entity-versions} and D2 of {@code add-relationships}): the store's entities and relationships, from which
 * a {@link Working} copy is made at the first touch, the temporary IDs of the ones the transaction creates, and the
 * namespace of IDs, which entities and relationships share. The parts of a {@link Preparation} work on one workspace.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Workspace {

    private final SchemaIndex index;
    private final Map<EntityId, StoredEntity> storedEntities;
    private final Map<RelationshipId, StoredRelationship> storedRelationships;
    private final Set<String> purgedIds;
    private final IdGenerator generator;
    private final ProblemLog problems;
    /** The entities and relationships the transaction touches, in the order of the first touch. */
    private final Map<Id, Working> working = new LinkedHashMap<>();
    /** Entities and relationships share one namespace of temporary IDs. */
    private final Map<TemporaryId, Working> temporaries = new HashMap<>();

    /**
     * Creates the workspace of a transaction.
     *
     * @param index               the schema the transaction is validated against
     * @param storedEntities      the entities of the store, ordered by ID
     * @param storedRelationships the relationships of the store, ordered by ID
     * @param purgedIds           the IDs of the entities and relationships that purges removed, which are never given
     *                            to a new entity or relationship
     * @param generator           a copy of the store's ID generator, which the preparation advances
     * @param problems            where problems about references and IDs are reported
     */
    Workspace(SchemaIndex index, Map<EntityId, StoredEntity> storedEntities,
            Map<RelationshipId, StoredRelationship> storedRelationships, Set<String> purgedIds, IdGenerator generator,
            ProblemLog problems) {
        this.index = index;
        this.storedEntities = storedEntities;
        this.storedRelationships = storedRelationships;
        this.purgedIds = purgedIds;
        this.generator = generator;
        this.problems = problems;
    }

    SchemaIndex index() {
        return index;
    }

    Map<EntityId, StoredEntity> storedEntities() {
        return storedEntities;
    }

    Map<RelationshipId, StoredRelationship> storedRelationships() {
        return storedRelationships;
    }

    IdGenerator generator() {
        return generator;
    }

    ProblemLog problems() {
        return problems;
    }

    /** The entities and relationships the transaction touches, in the order of the first touch. */
    Map<Id, Working> working() {
        return working;
    }

    // ------------------------------------------------------------------ references

    /** What problems about the target of a write call it: its temporary ID or its ID. */
    static String pathOf(Ref ref) {
        return switch (ref) {
            case TemporaryId temporaryId -> temporaryId.toString();
            case EntityId id -> id.value();
            case RelationshipId id -> id.value();
        };
    }

    /** Finds the entity or relationship a write targets, or reports that there is none. */
    Working resolve(Ref ref) {
        Working found = switch (ref) {
            case TemporaryId temporaryId -> resolveTemporaryId(temporaryId);
            case EntityId id -> loadEntity(id, true);
            case RelationshipId id -> loadRelationship(id, true);
        };
        if (found != null) {
            found.written = true;
        }
        return found;
    }

    Working resolveTemporaryId(TemporaryId temporaryId) {
        Working found = temporaries.get(temporaryId);
        if (found == null) {
            problems.add(Problems.of(ProblemCode.UNKNOWN_TEMPORARY_ID, temporaryId.toString(), "The temporary ID "
                    + temporaryId + " is not created by an earlier write of this transaction.",
                    "a temporary ID that an earlier create or relate of this transaction names",
                    temporaryId.toString(), null));
        }
        return found;
    }

    /** Finds an entity the transaction touches or the store holds, and starts to touch it. */
    Working loadEntity(EntityId id, boolean report) {
        Working found = working.get(id);
        if (found != null) {
            return found;
        }
        StoredEntity entity = storedEntities.get(id);
        if (entity == null) {
            if (report) {
                problems.add(Problems.of(ProblemCode.UNKNOWN_ENTITY, id.value(), "No entity has the ID " + id.value()
                        + ".", "the ID of an entity of the store", id.value(), null));
            }
            return null;
        }
        Working existing = Working.existingEntity(entity, index.entityType(entity.type()).orElse(null));
        working.put(id, existing);
        return existing;
    }

    /**
     * Finds a relationship the transaction touches or the store holds, and starts to touch it together with its ends,
     * whose multiplicities are checked because a relationship of theirs may change.
     */
    Working loadRelationship(RelationshipId id, boolean report) {
        Working found = working.get(id);
        if (found != null) {
            return found;
        }
        StoredRelationship relationship = storedRelationships.get(id);
        if (relationship == null) {
            if (report) {
                problems.add(Problems.of(ProblemCode.UNKNOWN_RELATIONSHIP, id.value(), "No relationship has the ID "
                        + id.value() + ".", "the ID of a relationship of the store", id.value(), null));
            }
            return null;
        }
        Working existing = Working.existingRelationship(relationship,
                index.relationshipType(relationship.type()).orElse(null));
        working.put(id, existing);
        loadEntity(relationship.source(), false);
        loadEntity(relationship.target(), false);
        return existing;
    }

    // ------------------------------------------------------------------ temporary IDs and IDs

    boolean hasTemporaryId(TemporaryId temporaryId) {
        return temporaries.containsKey(temporaryId);
    }

    void putTemporaryId(TemporaryId temporaryId, Working made) {
        temporaries.put(temporaryId, made);
    }

    /** Makes the transaction touch something it created. */
    void touch(Id id, Working made) {
        working.put(id, made);
    }

    /** What holds an ID in the store or in this transaction: {@code an entity} or {@code a relationship}; else null. */
    String holder(String text) {
        EntityId entity = new EntityId(text);
        RelationshipId relationship = new RelationshipId(text);
        if (working.containsKey(entity) || storedEntities.containsKey(entity)) {
            return "an entity";
        }
        if (working.containsKey(relationship) || storedRelationships.containsKey(relationship)) {
            return "a relationship";
        }
        return null;
    }

    boolean inStore(String text) {
        return storedEntities.containsKey(new EntityId(text)) || storedRelationships.containsKey(new RelationshipId(text));
    }

    /** Tells whether a purge removed the entity or relationship that had an ID (capability {@code purge}). */
    boolean purged(String text) {
        return purgedIds.contains(text);
    }

    /**
     * Draws an ID from the generator's copy that no entity or relationship has, also not one that a caller supplied, and
     * that no purge removed.
     */
    String drawId() {
        String id;
        do {
            id = generator.next();
        } while (holder(id) != null || purged(id));
        return id;
    }
}
