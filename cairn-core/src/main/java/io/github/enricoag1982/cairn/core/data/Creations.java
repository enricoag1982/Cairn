package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.ClosestName;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applies the writes that create something: the creation of an entity and the relating of two entities (design D5 of
 * {@code add-entity-versions} and D1 of {@code add-relationships}). The ends of a relationship can be named by unique
 * keys (ADR-0026). Problems about the new entity or relationship are
 * located at its temporary ID, else at the ID the caller supplied, else at {@code @write.<position>}: an ID that the
 * store generates differs between a dry run and a commit, so it cannot be the path of a problem that both report.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Creations {

    /** The start of the path of problems about something that has neither a temporary ID nor a supplied ID. */
    private static final String WRITE_PATH = "@write.";

    /** Whether a supplied ID can be used for the entity or relationship that is created. */
    private record IdCheck(boolean ok, boolean usable) {
    }

    private final Workspace workspace;
    private final ProblemLog problems;
    private final WriteChecks checks;
    private final CommitSequence.Commit commit;
    private final Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
    private final List<Id> created = new ArrayList<>();

    Creations(Workspace workspace, WriteChecks checks, CommitSequence.Commit commit) {
        this.workspace = workspace;
        this.problems = workspace.problems();
        this.checks = checks;
        this.commit = commit;
    }

    /** The ID of every entity or relationship named with a temporary ID, in the order of the writes. */
    Map<TemporaryId, Id> temporaryIds() {
        return temporaryIds;
    }

    /** The ID of every entity and relationship created, in the order of the writes. */
    List<Id> created() {
        return created;
    }

    /** Applies a create. */
    void create(Write.Create write, int position) {
        Optional<TemporaryId> temporaryId = write.temporaryId();
        String idText = write.id().map(EntityId::value).orElseGet(workspace::drawId);
        EntityId id = new EntityId(idText);
        String path = temporaryId.map(TemporaryId::toString)
                .or(() -> write.id().map(EntityId::value))
                .orElse(WRITE_PATH + position);
        boolean duplicateTemporaryId = duplicateTemporaryId(path, temporaryId);
        IdCheck idCheck = checkId(path, idText, write.id().isPresent());
        boolean ok = !duplicateTemporaryId && idCheck.ok();
        EffectivePeriod period = write.period();
        if (!checks.checkPeriod(path, period)) {
            ok = false;
        }
        EntityType type = entityType(path, write.entityType());
        WriteChecks.Resolved resolved = null;
        if (type != null) {
            resolved = checks.resolveValues(path, SubjectType.of(type), write.values());
        } else {
            ok = false;
        }
        Working entity;
        if (ok) {
            Timeline timeline = Timeline.created(period.from(), Segment.endOf(period), resolved.values(),
                    commit.transactionTime(), commit.basis());
            entity = Working.createdEntity(id, path, type, timeline);
            entity.written = true;
            entity.namesUnresolved = resolved.unresolvedNames();
            entity.derivedUnresolved = resolved.derivedNamed();
            created.add(id);
            temporaryId.ifPresent(temporary -> temporaryIds.put(temporary, id));
        } else {
            entity = Working.failed(id, path);
        }
        register(temporaryId, duplicateTemporaryId, idCheck, id, entity);
    }

    /**
     * Applies a relate. The ends are resolved like the targets of other writes, but are only read: an entity that is
     * only an end is not validated again, and none of its own rules run for the write, though re-checks can run them
     * (ADR-0025), and the multiplicities of its relationships are checked. A relationship that cannot be created,
     * because its type or an end is wrong, takes no part in those checks, and its ends are not reported for the
     * relationships they miss in consequence.
     */
    void relate(Write.Relate write, int position) {
        Optional<TemporaryId> temporaryId = write.temporaryId();
        String idText = write.id().map(RelationshipId::value).orElseGet(workspace::drawId);
        RelationshipId id = new RelationshipId(idText);
        String path = temporaryId.map(TemporaryId::toString)
                .or(() -> write.id().map(RelationshipId::value))
                .orElse(WRITE_PATH + position);
        boolean duplicateTemporaryId = duplicateTemporaryId(path, temporaryId);
        IdCheck idCheck = checkId(path, idText, write.id().isPresent());
        boolean ok = !duplicateTemporaryId && idCheck.ok();
        EffectivePeriod period = write.period();
        if (!checks.checkPeriod(path, period)) {
            ok = false;
        }
        RelationshipType type = relationshipType(path, write.relationshipType());
        Working source = resolveEnd(path, "source", write.source(), period, position);
        Working target = resolveEnd(path, "target", write.target(), period, position);
        WriteChecks.Resolved resolved = null;
        if (type != null) {
            resolved = checks.resolveValues(path, SubjectType.of(type), write.values());
        } else {
            ok = false;
        }
        if (source == null || target == null) {
            ok = false;
        } else if (type != null) {
            boolean sourceFits = checkEnd(path, type, "source", source, type.source());
            boolean targetFits = checkEnd(path, type, "target", target, type.target());
            ok &= sourceFits && targetFits;
        }
        if (source != null && target != null && (source.failed() || target.failed())) {
            ok = false; // The problem was reported when the end was created.
        }
        Working relationship;
        if (ok) {
            Timeline timeline = Timeline.created(period.from(), Segment.endOf(period), resolved.values(),
                    commit.transactionTime(), commit.basis());
            relationship = Working.createdRelationship(id, path, type, (EntityId) source.id, (EntityId) target.id,
                    timeline);
            relationship.written = true;
            relationship.namesUnresolved = resolved.unresolvedNames();
            relationship.derivedUnresolved = resolved.derivedNamed();
            created.add(id);
            temporaryId.ifPresent(temporary -> temporaryIds.put(temporary, id));
        } else {
            relationship = Working.failed(id, path);
            missesRelationship(source);
            missesRelationship(target);
        }
        register(temporaryId, duplicateTemporaryId, idCheck, id, relationship);
    }

    /** Remembers what a create or relate made, by temporary ID and ID. */
    private void register(Optional<TemporaryId> temporaryId, boolean duplicateTemporaryId, IdCheck idCheck, Id id,
            Working made) {
        if (temporaryId.isPresent() && !duplicateTemporaryId) {
            workspace.putTemporaryId(temporaryId.get(), made);
        }
        if (idCheck.usable()) {
            workspace.touch(id, made);
        }
    }

    /** A relationship that an entity should have could not be created: not having it is not a problem of its own. */
    private static void missesRelationship(Working end) {
        if (end != null && !end.failed() && !end.isRelationship()) {
            end.linksUnresolved = true;
        }
    }

    /** Reports a temporary ID that an earlier write of the transaction already created; tells whether it did. */
    private boolean duplicateTemporaryId(String path, Optional<TemporaryId> temporaryId) {
        if (temporaryId.isEmpty() || !workspace.hasTemporaryId(temporaryId.get())) {
            return false;
        }
        problems.add(Problems.of(ProblemCode.DUPLICATE_TEMPORARY_ID, path, "The temporary ID " + temporaryId.get()
                + " names two entities or relationships that this transaction creates.",
                "a temporary ID for each created entity or relationship", temporaryId.get().toString(), null));
        return true;
    }

    /**
     * Checks an ID that the caller supplied: it is in the generator's format, no entity or relationship has it, and no
     * purge removed an entity or relationship that had it (capability {@code purge}). IDs are unique across entities
     * and relationships.
     */
    private IdCheck checkId(String path, String idText, boolean supplied) {
        if (!supplied) {
            return new IdCheck(true, true);
        }
        if (!workspace.generator().accepts(idText)) {
            problems.add(Problems.of(ProblemCode.INVALID_ENTITY_ID, path, "The supplied ID '" + idText
                    + "' is not in the format of the store's ID generator.",
                    "an ID in the format of the store's ID generator", idText,
                    "Leave the ID out to let the store generate one."));
            return new IdCheck(false, false);
        }
        if (workspace.purged(idText)) {
            problems.add(Problems.of(ProblemCode.ENTITY_EXISTS, path, "The ID " + idText + " was purged: an entity or "
                    + "relationship that had it was removed, and a purged ID is never used again.",
                    "an ID that no entity or relationship has or had", idText,
                    "Leave the ID out to let the store generate one."));
            return new IdCheck(false, false);
        }
        String holder = workspace.holder(idText);
        if (holder != null) {
            problems.add(Problems.of(ProblemCode.ENTITY_EXISTS, path, holder.substring(0, 1).toUpperCase()
                    + holder.substring(1) + " with the ID " + idText
                    + (workspace.inStore(idText) ? " exists already." : " is created twice in this transaction."),
                    "an ID that no entity or relationship has", idText,
                    "Leave the ID out to let the store generate one."));
            return new IdCheck(false, false);
        }
        return new IdCheck(true, true);
    }

    /** Finds the concrete entity type of a create, or reports why there is none. */
    private EntityType entityType(String path, String name) {
        Optional<EntityType> found = workspace.index().schema().entityType(name);
        if (found.isEmpty()) {
            List<ClosestName.Candidate> candidates = workspace.index().schema().entityTypes().stream()
                    .flatMap(type -> ClosestName.candidates(type.name(), type.aliases()).stream())
                    .toList();
            problems.add(Problems.of(ProblemCode.UNKNOWN_ENTITY_TYPE, path, "The schema has no entity type '" + name
                    + "'.", "the name or an alias of a concrete entity type", name,
                    ClosestName.ofCandidates(name, candidates).map(closest -> "Did you mean '" + closest + "'?")
                            .orElse(null)));
            return null;
        }
        EntityType type = found.get();
        if (type.isAbstract()) {
            String concrete = type.subtypes().stream()
                    .map(subtype -> workspace.index().schema().entityType(subtype))
                    .flatMap(Optional::stream)
                    .filter(subtype -> !subtype.isAbstract())
                    .map(EntityType::name)
                    .sorted()
                    .collect(Collectors.joining(", "));
            problems.add(Problems.of(ProblemCode.ABSTRACT_ENTITY_TYPE, path, "The entity type " + type.name()
                    + " is abstract: it has no entities of its own.", "a concrete entity type", type.name(),
                    concrete.isEmpty() ? null : "Create the entity as one of: " + concrete + "."));
            return null;
        }
        return type;
    }

    /** Finds the relationship type of a relate, or reports why there is none. */
    private RelationshipType relationshipType(String path, String name) {
        Optional<RelationshipType> found = workspace.index().schema().relationshipType(name);
        if (found.isPresent()) {
            return found.get();
        }
        List<ClosestName.Candidate> candidates = workspace.index().schema().relationshipTypes().stream()
                .flatMap(type -> ClosestName.candidates(type.name(), type.aliases()).stream())
                .toList();
        problems.add(Problems.of(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, path, "The schema has no relationship type '"
                + name + "'.", "the name or an alias of a relationship type", name,
                ClosestName.ofCandidates(name, candidates).map(closest -> "Did you mean '" + closest + "'?")
                        .orElse(null)));
        return null;
    }

    /**
     * Finds an end of a relationship: an entity by its ID, by the temporary ID of an entity that an earlier write
     * creates, or by a unique key, which resolves at the start of the relationship's period (ADR-0026) and whose
     * problems are located at {@code @write.<position>}. Reports an end that is unknown or that is a relationship.
     */
    private Working resolveEnd(String path, String role, Ref named, EffectivePeriod period, int position) {
        Ref ref = workspace.resolveKey(named, period.from(), position);
        if (ref == null) {
            return null;
        }
        Working found = switch (ref) {
            case TemporaryId temporaryId -> workspace.resolveTemporaryId(temporaryId);
            case EntityId id -> workspace.loadEntity(id, true);
            case RelationshipId id -> {
                problems.add(wrongEnd(path, "The " + role + " of a relationship must be an entity, but "
                        + id.value() + " is the ID of a relationship.", "an entity", "the ID of a relationship"));
                yield null;
            }
            case UniqueKey key -> throw new IllegalStateException("The unique key " + key + " was not resolved.");
        };
        if (found != null && found.isRelationship()) {
            problems.add(wrongEnd(path, "The " + role + " of a relationship must be an entity, but "
                    + found.path + " is a relationship.", "an entity", "a relationship"));
            return null;
        }
        return found;
    }

    /** Checks that an end is an entity of the required type or of a subtype; tells whether it is. */
    private boolean checkEnd(String path, RelationshipType type, String role, Working end, String required) {
        if (end.failed()) {
            return true; // The problem was reported when the end was created.
        }
        EntityType actual = end.entityType;
        if (actual.name().equals(required) || actual.supertypes().contains(required)) {
            return true;
        }
        problems.add(wrongEnd(path, "The " + role + " of the " + type.name() + " relationship must be " + required
                + " or one of its subtypes, but " + end.path + " is " + actual.name() + ".",
                required + " or one of its subtypes", actual.name()));
        return false;
    }

    private static Problem wrongEnd(String path, String message, String expected, String found) {
        return Problems.of(ProblemCode.WRONG_END_TYPE, path, message, expected, found,
                "Relate entities of the types that the relationship type declares for its source and its target.");
    }
}
