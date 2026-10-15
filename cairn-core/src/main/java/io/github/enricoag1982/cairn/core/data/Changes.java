package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a transaction changes, in the terms of what rules read (design D2 of {@code add-rule-rechecks}): for each
 * {@link ReadKey}, the stretches of effective time in which it differs, found from the timeline of each entity and
 * relationship the transaction writes before and after its writes.
 *
 * <ul>
 *   <li>An entity whose values or existence differ in a stretch changes {@code Entity(id)} there. Where its existence
 *       differs, it also changes {@code OfType} of its type and of each supertype.
 *   <li>A relationship whose values or existence differ changes {@code Relationship(id)}. Where its existence differs, it
 *       also changes {@code Navigation(source, type, forward)} and {@code Navigation(target, type, inverse)} for its type
 *       and each supertype, and both directions at both ends if its type is symmetric.
 * </ul>
 *
 * <p>A boundary that splits a version without changing its values is no change. Finding more than this would run rules
 * that nothing they read has changed for, which is harmless for data that meets its rules, but would make results depend
 * on how a transaction cut its timelines; so this granularity is the specification, and tests pin it.
 *
 * <p>Built once per transaction, in the writer thread.
 */
final class Changes {

    private final Map<ReadKey, List<Stretch>> byKey = new LinkedHashMap<>();
    private final Map<Id, List<Stretch>> bySubject = new LinkedHashMap<>();

    private Changes() {
    }

    /**
     * Finds the changes of a transaction from its workspace.
     *
     * @param workspace the entities and relationships the transaction touches, with the timelines they had and have
     * @return the changes
     */
    static Changes of(Workspace workspace) {
        Changes changes = new Changes();
        Schema schema = workspace.index().schema();
        for (Working subject : workspace.working().values()) {
            if (subject.failed()) {
                continue;
            }
            List<Stretch> differing = subject.timeline.differences(subject.before, false);
            if (differing.isEmpty()) {
                continue;
            }
            changes.bySubject.put(subject.id, differing);
            List<Stretch> existing = subject.timeline.differences(subject.before, true);
            if (subject.isRelationship()) {
                changes.add(new ReadKey.Relationship((RelationshipId) subject.id),
                        differing);
                if (!existing.isEmpty()) {
                    changes.relationshipExistence(schema, subject, existing);
                }
            } else {
                changes.add(new ReadKey.Entity((EntityId) subject.id), differing);
                if (!existing.isEmpty()) {
                    for (TypeId type : entityTypes(schema, subject.entityType)) {
                        changes.add(new ReadKey.OfType(type), existing);
                    }
                }
            }
        }
        return changes;
    }

    /** The stretches of effective time in which each key changes. */
    Map<ReadKey, List<Stretch>> byKey() {
        return byKey;
    }

    /** The stretches of effective time in which each written entity or relationship differs, by its ID. */
    Map<Id, List<Stretch>> bySubject() {
        return bySubject;
    }

    private void relationshipExistence(Schema schema, Working subject, List<Stretch> existing) {
        boolean symmetric = subject.relationshipType.symmetric();
        for (TypeId type : relationshipTypes(schema, subject.relationshipType)) {
            add(new ReadKey.Navigation(subject.source, type, true), existing);
            add(new ReadKey.Navigation(subject.target, type, false), existing);
            if (symmetric) {
                add(new ReadKey.Navigation(subject.source, type, false), existing);
                add(new ReadKey.Navigation(subject.target, type, true), existing);
            }
        }
    }

    private void add(ReadKey key, List<Stretch> stretches) {
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).addAll(stretches);
    }

    /** An entity type and its supertypes, nearest first. */
    private static List<TypeId> entityTypes(Schema schema, EntityType type) {
        List<TypeId> types = new ArrayList<>();
        types.add(type.id());
        for (String supertype : type.supertypes()) {
            schema.entityType(supertype).ifPresent(found -> types.add(found.id()));
        }
        return types;
    }

    /** A relationship type and its supertypes, nearest first. */
    private static List<TypeId> relationshipTypes(Schema schema, RelationshipType type) {
        List<TypeId> types = new ArrayList<>();
        types.add(type.id());
        for (String supertype : type.supertypes()) {
            schema.relationshipType(supertype).ifPresent(found -> types.add(found.id()));
        }
        return types;
    }
}
