package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ends the compositions of every entity that a write ended (design D4 of {@code add-relationships}): the composition
 * relationships with the entity as their source end from the same instant, and so do their targets, recursively. A
 * target ends with its source, so every other relationship of a target that the cascade ends ends with it too, at
 * either end and of any type, once the target's own compositions have ended (design D1 of
 * {@code change-composition-cascades}). The entities at the other ends of such a relationship become touched, as the
 * ends of a relationship are, so that their multiplicities are checked; nothing else is validated again. An entity that
 * a write ends is not a target: its relationships that continue stay the writer's to end. The added ends are part of
 * the transaction. Only what exists before the instant and goes on past it is ended; a relationship or target that
 * starts at or after the instant is left as it is, for the integrity checks to report, because ending it would erase
 * it, and so is a relationship that the transaction already ends from the instant or earlier.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Cascades {

    /**
     * An entity that a write ends, and the first instant at which it no longer exists.
     *
     * @param entity the entity
     * @param from   the first instant at which it no longer exists
     */
    record Ended(Working entity, Instant from) {
    }

    private final Workspace workspace;
    /** The relationships and entities that cascades ended, in the order in which the cascades ended them. */
    private final Set<Id> endedIds = new LinkedHashSet<>();

    Cascades(Workspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Cascades from every entity that a write ended.
     *
     * @param ended the entities the writes ended, in the order of the writes
     * @return the IDs of the relationships and entities that the cascades ended, not those that writes ended: for each
     *         ended entity in turn, each composition relationship in the order of its ID, then its target, then what
     *         the cascade ends from that target in the same way, depth first, and then the other relationships of that
     *         target that the cascade ends, in the order of their IDs, before the next composition relationship; each
     *         ID once
     */
    List<Id> run(List<Ended> ended) {
        for (Ended end : ended) {
            cascadeFrom(end.entity(), end.from(), new HashSet<>());
        }
        return List.copyOf(endedIds);
    }

    private void cascadeFrom(Working source, Instant from, Set<EntityId> visited) {
        EntityId sourceId = (EntityId) source.id;
        if (!visited.add(sourceId)) {
            return;
        }
        for (Working relationship : compositionsFrom(sourceId)) {
            if (!goesPast(relationship.timeline, from)) {
                continue;
            }
            relationship.timeline = relationship.timeline.endAt(from);
            relationship.written = true;
            endedIds.add(relationship.id);
            Working target = workspace.loadEntity(relationship.target, false);
            if (target != null && !target.failed() && goesPast(target.timeline, from)) {
                target.timeline = target.timeline.endAt(from);
                target.written = true;
                endedIds.add(target.id);
                cascadeFrom(target, from, visited);
                endRelationshipsOf((EntityId) target.id, from);
            }
        }
    }

    /**
     * Ends the relationships of an entity that the cascade ended, of any type and at either end, that start before the
     * instant and go on past it, in the order of their IDs. The entities at their other ends become touched.
     */
    private void endRelationshipsOf(EntityId entity, Instant from) {
        for (Working relationship : relationshipsPast(entity, from)) {
            relationship.timeline = relationship.timeline.endAt(from);
            relationship.written = true;
            endedIds.add(relationship.id);
            workspace.loadEntity(relationship.source, false);
            workspace.loadEntity(relationship.target, false);
        }
    }

    /**
     * The composition relationships with an entity as their source, whether the transaction touches them or not, in the
     * order of their IDs.
     */
    private List<Working> compositionsFrom(EntityId source) {
        List<Working> found = new ArrayList<>();
        for (Working subject : List.copyOf(workspace.working().values())) {
            if (subject.isRelationship() && !subject.failed() && source.equals(subject.source)
                    && subject.relationshipType.kind() == RelationshipKind.COMPOSITION) {
                found.add(subject);
            }
        }
        for (StoredRelationship stored : workspace.storedRelationships().values()) {
            if (!workspace.working().containsKey(stored.id()) && source.equals(stored.source())
                    && workspace.index().relationshipType(stored.type())
                            .map(type -> type.kind() == RelationshipKind.COMPOSITION).orElse(false)) {
                found.add(workspace.loadRelationship(stored.id(), false));
            }
        }
        found.sort(Comparator.comparing(relationship -> (RelationshipId) relationship.id));
        return found;
    }

    /**
     * The relationships of types that the schema has, with an entity as their source or their target, that start before
     * an instant and go on past it, whether the transaction touches them or not, in the order of their IDs. A
     * relationship that the transaction does not touch is touched from here on, with its ends.
     */
    private List<Working> relationshipsPast(EntityId entity, Instant from) {
        List<Working> found = new ArrayList<>();
        for (Working subject : List.copyOf(workspace.working().values())) {
            if (subject.isRelationship() && !subject.failed()
                    && (entity.equals(subject.source) || entity.equals(subject.target))
                    && goesPast(subject.timeline, from)) {
                found.add(subject);
            }
        }
        for (StoredRelationship stored : workspace.storedRelationships().values()) {
            if (!workspace.working().containsKey(stored.id())
                    && (entity.equals(stored.source()) || entity.equals(stored.target()))
                    && goesPast(stored.latest(), from)
                    && workspace.index().relationshipType(stored.type()).isPresent()) {
                found.add(workspace.loadRelationship(stored.id(), false));
            }
        }
        found.sort(Comparator.comparing(relationship -> (RelationshipId) relationship.id));
        return found;
    }

    /** Tells whether something starts before an instant and goes on past it, so that ending it there cuts it off. */
    private static boolean goesPast(Timeline timeline, Instant instant) {
        return timeline.lifetime()
                .map(lifetime -> lifetime.from().isBefore(instant) && lifetime.end().isAfter(instant))
                .orElse(false);
    }
}
