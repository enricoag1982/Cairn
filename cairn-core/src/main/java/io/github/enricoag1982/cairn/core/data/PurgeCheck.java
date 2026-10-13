package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.storage.IdDigests;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a purge and works out what it removes (ADR-0024, design D2 of {@code add-purge}, capability {@code purge}),
 * without changing anything.
 *
 * <p>The set to remove is the entities that the purge names, then the targets of their compositions, transitively, then
 * every relationship from or to any of them. The targets of compositions are found as {@link Cascades} finds them for
 * end-dating, except that a purge removes whole histories: every composition relationship of the entity counts, whenever
 * it is effective, and so does every target it ever had. A target goes with its sources only if every composition
 * relationship that ever had it as target, at any basis, has a source in the set; a target that another entity composed
 * too stays, and the minimums below find it, because a composition needs a source while its target exists.
 *
 * <p>A purge is refused for two reasons. An ID that no entity has is {@code UNKNOWN_ENTITY}, at {@code @purge.<n>}.
 * And what remains must still hold the minimums of the relationship types: for every entity that remains and had a
 * relationship removed, {@link Integrity} checks, in the state that the purge would leave, the multiplicities at every
 * point in effective time in which the entity exists; a minimum that no longer holds is {@code TOO_FEW_RELATIONSHIPS},
 * with the entity's ID as its path, so that the caller can add it to the purge, and the hint to purge it too. Every
 * problem is reported. Rules do not run: a purge writes no versions.
 *
 * <p>Used with the writer lock held.
 */
final class PurgeCheck {

    /** The start of the path of problems about an entity that a purge names. */
    static final String PURGE_PATH = "@purge.";

    /**
     * What a purge removes, or why it is refused.
     *
     * @param entities      the entities to remove: the named ones, in the order given and without repeats, and then the
     *                      targets of their compositions, depth first
     * @param relationships the relationships to remove, in the order of their IDs
     * @param problems      every problem found; if there is one, nothing is to be removed
     */
    record Outcome(List<EntityId> entities, List<RelationshipId> relationships, List<Problem> problems) {
    }

    private final Purge purge;
    private final SchemaIndex index;
    private final Map<EntityId, StoredEntity> entities;
    private final Map<RelationshipId, StoredRelationship> relationships;
    private final Set<String> purgedDigests;
    private final List<Problem> problems = new ArrayList<>();

    private PurgeCheck(Purge purge, SchemaIndex index, Map<EntityId, StoredEntity> entities,
            Map<RelationshipId, StoredRelationship> relationships, Set<String> purgedDigests) {
        this.purge = purge;
        this.index = index;
        this.entities = entities;
        this.relationships = relationships;
        this.purgedDigests = purgedDigests;
    }

    /**
     * Checks a purge against the latest state of a store.
     *
     * @param purge         the purge
     * @param index         the current schema of the store
     * @param entities      the entities of the store, ordered by ID
     * @param relationships the relationships of the store, ordered by ID
     * @param purgedDigests the digests of the IDs that earlier purges removed
     * @return what the purge removes, or the problems that refuse it
     */
    static Outcome run(Purge purge, SchemaIndex index, Map<EntityId, StoredEntity> entities,
            Map<RelationshipId, StoredRelationship> relationships, Set<String> purgedDigests) {
        return new PurgeCheck(purge, index, entities, relationships, purgedDigests).check();
    }

    private Outcome check() {
        Set<EntityId> removed = new LinkedHashSet<>();
        List<EntityId> given = purge.entities();
        for (int position = 0; position < given.size(); position++) {
            EntityId id = given.get(position);
            if (entities.containsKey(id)) {
                removed.add(id);
            } else {
                problems.add(unknown(id, position));
            }
        }
        List<EntityId> named = List.copyOf(removed);
        Compositions compositions = compositions();
        for (EntityId id : named) {
            addCompositionTargets(id, compositions, removed);
        }
        List<StoredRelationship> removedRelationships = new ArrayList<>();
        for (StoredRelationship relationship : relationships.values()) {
            if (removed.contains(relationship.source()) || removed.contains(relationship.target())) {
                removedRelationships.add(relationship);
            }
        }
        checkMinimums(removed, removedRelationships);
        return new Outcome(List.copyOf(removed), removedRelationships.stream().map(StoredRelationship::id).toList(),
                List.copyOf(problems));
    }

    private Problem unknown(EntityId id, int position) {
        String text = id.value();
        boolean purged = !purgedDigests.isEmpty() && purgedDigests.contains(IdDigests.of(text));
        String why = relationships.containsKey(new RelationshipId(text))
                ? " The ID is the ID of a relationship, and a purge names entities."
                : purged ? " The entity was purged earlier." : "";
        return Problems.of(ProblemCode.UNKNOWN_ENTITY, PURGE_PATH + position, "No entity has the ID " + text + "."
                + why, "the ID of an entity of the store", text,
                "Correct the ID, or leave it out of the purge.");
    }

    /**
     * The composition relationships of the store, which are every one that was ever recorded at any basis, by their source
     * entity and by their target entity, each list in the order of the IDs.
     */
    private record Compositions(Map<EntityId, List<StoredRelationship>> bySource,
            Map<EntityId, List<StoredRelationship>> byTarget) {
    }

    private Compositions compositions() {
        Map<EntityId, List<StoredRelationship>> bySource = new HashMap<>();
        Map<EntityId, List<StoredRelationship>> byTarget = new HashMap<>();
        for (StoredRelationship relationship : relationships.values()) {
            boolean composition = index.relationshipType(relationship.type())
                    .map(type -> type.kind() == RelationshipKind.COMPOSITION).orElse(false);
            if (composition) {
                bySource.computeIfAbsent(relationship.source(), key -> new ArrayList<>()).add(relationship);
                byTarget.computeIfAbsent(relationship.target(), key -> new ArrayList<>()).add(relationship);
            }
        }
        return new Compositions(bySource, byTarget);
    }

    /**
     * Adds the targets of the compositions of an entity that no entity outside the set composed, each followed by its
     * own, depth first. A target that has a composition from an entity that is not in the set stays, for now: it is added
     * when the last of its sources is, because that source has a composition to it, which is looked at then.
     */
    private void addCompositionTargets(EntityId source, Compositions compositions, Set<EntityId> removed) {
        for (StoredRelationship relationship : compositions.bySource().getOrDefault(source, List.of())) {
            EntityId target = relationship.target();
            if (entities.containsKey(target) && !removed.contains(target)
                    && composedOnlyBy(target, compositions, removed)) {
                removed.add(target);
                addCompositionTargets(target, compositions, removed);
            }
        }
    }

    /** Tells whether every composition relationship that ever had the entity as its target has its source in the set. */
    private static boolean composedOnlyBy(EntityId target, Compositions compositions, Set<EntityId> removed) {
        return compositions.byTarget().getOrDefault(target, List.of()).stream()
                .allMatch(relationship -> removed.contains(relationship.source()));
    }

    /**
     * Reports the entities that would be left with fewer relationships than a type's minimum. Only the entities that
     * remain and had a relationship removed can be: the others are unchanged, or removed.
     */
    private void checkMinimums(Set<EntityId> removed, List<StoredRelationship> removedRelationships) {
        Set<EntityId> affected = new TreeSet<>();
        Map<RelationshipId, StoredRelationship> remaining = new HashMap<>(relationships);
        for (StoredRelationship relationship : removedRelationships) {
            remaining.remove(relationship.id());
            affected.add(relationship.source());
            affected.add(relationship.target());
        }
        affected.removeAll(removed);
        Map<Id, Working> working = new LinkedHashMap<>();
        for (EntityId id : affected) {
            StoredEntity stored = entities.get(id);
            Optional<EntityType> type = stored == null ? Optional.empty() : index.entityType(stored.type());
            if (type.isPresent()) {
                working.put(id, Working.existingEntity(stored, type.get()));
            }
        }
        if (working.isEmpty()) {
            return;
        }
        Integrity integrity = new Integrity(index, working, entities, remaining);
        for (Working entity : working.values()) {
            for (Problem problem : integrity.entityProblems(entity)) {
                if (problem.code() == ProblemCode.TOO_FEW_RELATIONSHIPS) {
                    problems.add(withAdvice(problem, entity.path));
                }
            }
        }
    }

    /**
     * The problem of an entity that would lack relationships, as a purge reports it: at the ID of the entity, which is
     * what the caller adds to the purge, with what the caller can do about it. The message, which names the relationship
     * type and the stretch of effective time, is {@link Integrity}'s.
     */
    private static Problem withAdvice(Problem problem, String entity) {
        return new Problem(problem.code(), entity, problem.message() + " The purge removes the relationships "
                + "that the entity has with the entities it purges.", problem.expected(), problem.found(),
                Optional.of("Purge " + entity + " too, in the same purge, or leave out the entities that " + entity
                        + " needs."));
    }
}
