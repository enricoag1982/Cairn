package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.ClosestName;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies the writes of one transaction to working copies of the timelines it touches, validates the state that
 * results (design D5 and D6 of {@code add-entity-versions}), checks the integrity of relationships over effective time
 * (design D3 and D4 of {@code add-relationships}), and runs the rules that apply to it (design D3 of
 * {@code add-entity-rules}). Nothing in the store changes: a commit appends {@link #changes()} afterwards, and a dry
 * run drops them.
 *
 * <p>Writes are applied in order. A write with problems is applied as far as it can be, so that the problems of the
 * resulting state are found too: every problem of the transaction is reported, not only the first. When the writes are
 * applied, ending an entity ends its compositions too. Problems come in a fixed order: those of the transaction
 * itself, then those of each write in the order of the writes, then those of each entity and relationship (the
 * subjects) in the order in which the writes first touched them, and last those of the rules. The problems of a
 * subject are those of its values, which follow the order of the attributes in the schema, and then those of its
 * relationships.
 *
 * <p>A Preparation is used once and by one thread: the one holding the store's writer lock.
 */
final class Preparation {

    /** The path of problems about the transaction itself. */
    static final String TRANSACTION_PATH = "@transaction";

    /** The start of the path of problems about something that has neither a temporary ID nor a supplied ID. */
    private static final String WRITE_PATH = "@write.";

    /**
     * What a commit records for an entity or a relationship.
     *
     * @param id       the ID of the entity or relationship
     * @param type     its type
     * @param source   the source of a relationship; null for an entity
     * @param target   the target of a relationship; null for an entity
     * @param created  whether the transaction creates it
     * @param timeline its timeline after the transaction
     */
    record Change(Id id, TypeId type, EntityId source, EntityId target, boolean created, Timeline timeline) {
    }

    /** Values of a write resolved to attributes. */
    private record Resolved(Map<AttributeId, Value> values, List<Attribute> attributes, boolean unresolvedNames) {
    }

    /** Whether a supplied ID can be used for the entity or relationship that is created. */
    private record IdCheck(boolean ok, boolean usable) {
    }

    /** An entity that a write ends, and the first instant at which it no longer exists. */
    private record Ended(Working entity, Instant from) {
    }

    private final Transaction transaction;
    private final SchemaIndex index;
    private final Map<EntityId, StoredEntity> storedEntities;
    private final Map<RelationshipId, StoredRelationship> storedRelationships;
    private final IdGenerator generator;
    private final CommitSequence.Commit commit;
    private final ValueChecks checks;
    private final Rules rules;
    private final CommitSequence sequence;

    private final List<Problem> problems = new ArrayList<>();
    /** The entities and relationships the transaction touches, in the order of the first touch. */
    private final Map<Id, Working> working = new LinkedHashMap<>();
    /** Entities and relationships share one namespace of temporary IDs. */
    private final Map<TemporaryId, Working> temporaries = new HashMap<>();
    private final Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
    private final List<Id> created = new ArrayList<>();
    private final List<Ended> ended = new ArrayList<>();

    /**
     * Prepares a transaction.
     *
     * @param transaction         the transaction
     * @param index               the schema the transaction is validated against
     * @param storedEntities      the entities of the store, ordered by ID
     * @param storedRelationships the relationships of the store, ordered by ID
     * @param generator           a copy of the store's ID generator, which this preparation advances
     * @param commit              the basis and transaction time the transaction would get
     * @param checks              the value checks
     * @param rules               the rules with code that run once the declarative checks have passed
     * @param sequence            the store's sequence, which the caller has locked for writing and which refuses
     *                            writes while the rules run
     */
    Preparation(Transaction transaction, SchemaIndex index, Map<EntityId, StoredEntity> storedEntities,
            Map<RelationshipId, StoredRelationship> storedRelationships, IdGenerator generator,
            CommitSequence.Commit commit, ValueChecks checks, Rules rules, CommitSequence sequence) {
        this.transaction = transaction;
        this.index = index;
        this.storedEntities = storedEntities;
        this.storedRelationships = storedRelationships;
        this.generator = generator;
        this.commit = commit;
        this.checks = checks;
        this.rules = rules;
        this.sequence = sequence;
    }

    /** Applies and validates the transaction; returns this. */
    Preparation run() {
        if (transaction.principal().isBlank()) {
            problems.add(Problems.of(ProblemCode.MISSING_PRINCIPAL, TRANSACTION_PATH,
                    "The transaction names no principal.", "the principal that submits the transaction, such as "
                    + "agent:onboarding", transaction.principal().isEmpty() ? "no principal" : "a blank principal",
                    null));
        }
        List<Write> writes = transaction.writes();
        for (int position = 0; position < writes.size(); position++) {
            switch (writes.get(position)) {
                case Write.Create create -> create(create, position);
                case Write.Relate relate -> relate(relate, position);
                case Write.Patch patch -> patch(patch);
                case Write.Replace replace -> replace(replace);
                case Write.EndDate endDate -> endDate(endDate);
            }
        }
        cascade();
        Integrity integrity = new Integrity(index, working, storedEntities, storedRelationships);
        for (Working subject : List.copyOf(working.values())) {
            if (subject.failed()) {
                continue;
            }
            if (subject.written) {
                validate(subject);
            }
            problems.addAll(subject.isRelationship() ? integrity.relationshipProblems(subject)
                    : integrity.entityProblems(subject));
        }
        runRules();
        return this;
    }

    List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** The ID of every entity or relationship named with a temporary ID, in the order of the writes. */
    Map<TemporaryId, Id> temporaryIds() {
        return temporaryIds;
    }

    /** The ID of every entity and relationship created, in the order of the writes. */
    List<Id> created() {
        return created;
    }

    /** The generator after the IDs of this transaction were drawn. */
    IdGenerator generator() {
        return generator;
    }

    CommitSequence.Commit commit() {
        return commit;
    }

    /**
     * What a commit records: the entities and relationships that were created or whose state changed, in order of first
     * touch. One whose writes left it as it was, such as one that a transaction changes and changes back, records
     * nothing, even if the writes split its timeline into parts that different transactions recorded. An entity that is
     * only the end of a relationship is not changed.
     */
    List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        for (Working subject : working.values()) {
            if (!subject.failed() && (subject.created || !subject.timeline.sameState(subject.before))) {
                TypeId type = subject.isRelationship() ? subject.relationshipType.id() : subject.entityType.id();
                changes.add(new Change(subject.id, type, subject.source, subject.target, subject.created,
                        subject.timeline));
            }
        }
        return changes;
    }

    // ------------------------------------------------------------------ create and relate

    /**
     * Applies a create. Problems about the new entity are located at its temporary ID, else at the ID the caller
     * supplied, else at {@code @write.<position>}: an ID that the store generates differs between a dry run and a
     * commit, so it cannot be the path of a problem that both report.
     */
    private void create(Write.Create write, int position) {
        Optional<TemporaryId> temporaryId = write.temporaryId();
        String idText = write.id().map(EntityId::value).orElseGet(this::drawId);
        EntityId id = new EntityId(idText);
        String path = temporaryId.map(TemporaryId::toString)
                .or(() -> write.id().map(EntityId::value))
                .orElse(WRITE_PATH + position);
        boolean duplicateTemporaryId = duplicateTemporaryId(path, temporaryId);
        IdCheck idCheck = checkId(path, idText, write.id().isPresent());
        boolean ok = !duplicateTemporaryId && idCheck.ok();
        EffectivePeriod period = write.period();
        if (!checkPeriod(path, period)) {
            ok = false;
        }
        EntityType type = entityType(path, write.entityType());
        Resolved resolved = null;
        if (type != null) {
            resolved = resolveValues(path, SubjectType.of(type), write.values());
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
            created.add(id);
            temporaryId.ifPresent(temporary -> temporaryIds.put(temporary, id));
        } else {
            entity = Working.failed(id, path);
        }
        register(temporaryId, duplicateTemporaryId, idCheck, id, entity);
    }

    /**
     * Applies a relate. Problems about the new relationship are located like those of a new entity, at its temporary ID,
     * the ID the caller supplied, or {@code @write.<position>}. The ends are resolved like the targets of other writes,
     * but are only read: an entity that is only an end is not validated again and has no rules run, though the
     * multiplicities of its relationships are checked. A relationship that cannot be created, because its type or an end
     * is wrong, takes no part in those checks, and its ends are not reported for the relationships they miss in
     * consequence.
     */
    private void relate(Write.Relate write, int position) {
        Optional<TemporaryId> temporaryId = write.temporaryId();
        String idText = write.id().map(RelationshipId::value).orElseGet(this::drawId);
        RelationshipId id = new RelationshipId(idText);
        String path = temporaryId.map(TemporaryId::toString)
                .or(() -> write.id().map(RelationshipId::value))
                .orElse(WRITE_PATH + position);
        boolean duplicateTemporaryId = duplicateTemporaryId(path, temporaryId);
        IdCheck idCheck = checkId(path, idText, write.id().isPresent());
        boolean ok = !duplicateTemporaryId && idCheck.ok();
        EffectivePeriod period = write.period();
        if (!checkPeriod(path, period)) {
            ok = false;
        }
        RelationshipType type = relationshipType(path, write.relationshipType());
        Working source = resolveEnd(path, "source", write.source());
        Working target = resolveEnd(path, "target", write.target());
        Resolved resolved = null;
        if (type != null) {
            resolved = resolveValues(path, SubjectType.of(type), write.values());
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
            temporaries.put(temporaryId.get(), made);
        }
        if (idCheck.usable()) {
            working.put(id, made);
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
        if (temporaryId.isEmpty() || !temporaries.containsKey(temporaryId.get())) {
            return false;
        }
        problems.add(Problems.of(ProblemCode.DUPLICATE_TEMPORARY_ID, path, "The temporary ID " + temporaryId.get()
                + " names two entities or relationships that this transaction creates.",
                "a temporary ID for each created entity or relationship", temporaryId.get().toString(), null));
        return true;
    }

    /**
     * Checks an ID that the caller supplied: it is in the generator's format, and no entity or relationship has it.
     * IDs are unique across entities and relationships.
     */
    private IdCheck checkId(String path, String idText, boolean supplied) {
        if (!supplied) {
            return new IdCheck(true, true);
        }
        if (!generator.accepts(idText)) {
            problems.add(Problems.of(ProblemCode.INVALID_ENTITY_ID, path, "The supplied ID '" + idText
                    + "' is not in the format of the store's ID generator.",
                    "an ID in the format of the store's ID generator", idText,
                    "Leave the ID out to let the store generate one."));
            return new IdCheck(false, false);
        }
        String holder = holder(idText);
        if (holder != null) {
            problems.add(Problems.of(ProblemCode.ENTITY_EXISTS, path, holder.substring(0, 1).toUpperCase()
                    + holder.substring(1) + " with the ID " + idText
                    + (inStore(idText) ? " exists already." : " is created twice in this transaction."),
                    "an ID that no entity or relationship has", idText,
                    "Leave the ID out to let the store generate one."));
            return new IdCheck(false, false);
        }
        return new IdCheck(true, true);
    }

    /** Finds the concrete entity type of a create, or reports why there is none. */
    private EntityType entityType(String path, String name) {
        Optional<EntityType> found = index.schema().entityType(name);
        if (found.isEmpty()) {
            List<ClosestName.Candidate> candidates = index.schema().entityTypes().stream()
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
                    .map(subtype -> index.schema().entityType(subtype))
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
        Optional<RelationshipType> found = index.schema().relationshipType(name);
        if (found.isPresent()) {
            return found.get();
        }
        List<ClosestName.Candidate> candidates = index.schema().relationshipTypes().stream()
                .flatMap(type -> ClosestName.candidates(type.name(), type.aliases()).stream())
                .toList();
        problems.add(Problems.of(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, path, "The schema has no relationship type '"
                + name + "'.", "the name or an alias of a relationship type", name,
                ClosestName.ofCandidates(name, candidates).map(closest -> "Did you mean '" + closest + "'?")
                        .orElse(null)));
        return null;
    }

    /**
     * Finds an end of a relationship: an entity by its ID, or by the temporary ID of an entity that an earlier write
     * creates. Reports an end that is unknown or that is a relationship.
     */
    private Working resolveEnd(String path, String role, Ref ref) {
        Working found = switch (ref) {
            case TemporaryId temporaryId -> resolveTemporaryId(temporaryId);
            case EntityId id -> loadEntity(id, true);
            case RelationshipId id -> {
                problems.add(wrongEnd(path, "The " + role + " of a relationship must be an entity, but "
                        + id.value() + " is the ID of a relationship.", "an entity", "the ID of a relationship"));
                yield null;
            }
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

    // ------------------------------------------------------------------ patch, replace, end-date

    private void patch(Write.Patch write) {
        String path = path(write.target());
        Working target = resolve(write.target());
        boolean validPeriod = checkPeriod(path, write.period());
        if (target == null || target.failed()) {
            return;
        }
        SubjectType type = target.type();
        Resolved set = resolveValues(path, type, write.set());
        Set<AttributeId> cleared = new LinkedHashSet<>();
        List<Attribute> affected = new ArrayList<>(set.attributes());
        boolean unresolved = set.unresolvedNames();
        for (String name : write.clear()) {
            Optional<Attribute> attribute = type.attribute(name);
            if (attribute.isEmpty()) {
                unknownAttribute(path, type, name);
                unresolved = true;
            } else if (cleared.add(attribute.get().id()) && !affected.contains(attribute.get())) {
                affected.add(attribute.get());
            }
        }
        target.namesUnresolved |= unresolved;
        checkConflict(write.check(), target, path, affected);
        if (validPeriod && isEffective(target, path, write.period())) {
            target.timeline = target.timeline.update(write.period().from(), Segment.endOf(write.period()), old -> {
                Map<AttributeId, Value> changed = new HashMap<>(old);
                cleared.forEach(changed::remove);
                changed.putAll(set.values());
                return changed;
            }, commit.transactionTime(), commit.basis());
        }
    }

    private void replace(Write.Replace write) {
        String path = path(write.target());
        Working target = resolve(write.target());
        boolean validPeriod = checkPeriod(path, write.period());
        if (target == null || target.failed()) {
            return;
        }
        Resolved values = resolveValues(path, target.type(), write.values());
        target.namesUnresolved |= values.unresolvedNames();
        checkConflict(write.check(), target, path, null);
        if (validPeriod && isEffective(target, path, write.period())) {
            target.timeline = target.timeline.update(write.period().from(), Segment.endOf(write.period()),
                    old -> values.values(), commit.transactionTime(), commit.basis());
        }
    }

    private void endDate(Write.EndDate write) {
        String path = path(write.target());
        Working target = resolve(write.target());
        if (target == null || target.failed()) {
            return;
        }
        checkConflict(write.check(), target, path, null);
        Optional<Timeline.Lifetime> lifetime = target.timeline.lifetime();
        if (lifetime.isEmpty() || !write.from().isAfter(lifetime.get().from())) {
            problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The " + target.kind() + " cannot be ended "
                    + "from " + write.from() + (lifetime.isPresent() ? ", because it exists only from "
                    + lifetime.get().from() + " on." : "."), "an instant after the start of the " + target.kind(),
                    write.from().toString(), null));
            return;
        }
        target.timeline = target.timeline.endAt(write.from());
        if (!target.isRelationship()) {
            ended.add(new Ended(target, write.from()));
        }
    }

    /** Reports a period that does not end after it starts; tells whether the period is valid. */
    private boolean checkPeriod(String path, EffectivePeriod period) {
        if (period.endsAfterStart()) {
            return true;
        }
        problems.add(Problems.of(ProblemCode.INVALID_PERIOD, path, "The effective period from " + period.from()
                + " until " + period.until().orElseThrow() + " does not end after it starts.",
                "an end after " + period.from(), period.until().orElseThrow().toString(), null));
        return false;
    }

    /** Reports a period that the target does not exist throughout; tells whether it exists throughout it. */
    private boolean isEffective(Working target, String path, EffectivePeriod period) {
        if (target.timeline.covers(period.from(), Segment.endOf(period))) {
            return true;
        }
        String lifetime = target.timeline.lifetime()
                .map(found -> "exists " + Segment.describe(found.from(), found.end()))
                .orElse("does not exist");
        problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The " + target.kind() + " " + lifetime
                + ", so it cannot be changed " + Segment.describe(period.from(), Segment.endOf(period)) + ".",
                "a period within the lifetime of the " + target.kind(),
                Segment.describe(period.from(), Segment.endOf(period)), null));
        return false;
    }

    // ------------------------------------------------------------------ composition cascades

    /**
     * Ends the compositions of every entity that a write ended (design D4 of {@code add-relationships}): the
     * composition relationships with the entity as their source end from the same instant, and so do their targets,
     * recursively. The added ends are part of the transaction. Only what exists before the instant and goes on past it
     * is ended; a relationship or target that starts at or after the instant is left as it is, for the integrity checks
     * to report, because ending it would erase it. A target that still has relationships that continue is reported by
     * those checks too.
     */
    private void cascade() {
        for (Ended end : ended) {
            cascadeFrom(end.entity(), end.from(), new HashSet<>());
        }
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
            Working target = loadEntity(relationship.target, false);
            if (target != null && !target.failed() && goesPast(target.timeline, from)) {
                target.timeline = target.timeline.endAt(from);
                target.written = true;
                cascadeFrom(target, from, visited);
            }
        }
    }

    /** The composition relationships with an entity as their source, whether the transaction touches them or not. */
    private List<Working> compositionsFrom(EntityId source) {
        List<Working> found = new ArrayList<>();
        for (Working subject : List.copyOf(working.values())) {
            if (subject.isRelationship() && !subject.failed() && source.equals(subject.source)
                    && subject.relationshipType.kind() == RelationshipKind.COMPOSITION) {
                found.add(subject);
            }
        }
        for (StoredRelationship stored : storedRelationships.values()) {
            if (!working.containsKey(stored.id()) && source.equals(stored.source())
                    && index.relationshipType(stored.type()).map(type -> type.kind() == RelationshipKind.COMPOSITION)
                            .orElse(false)) {
                found.add(loadRelationship(stored.id(), false));
            }
        }
        return found;
    }

    /** Tells whether something starts before an instant and goes on past it, so that ending it there cuts it off. */
    private static boolean goesPast(Timeline timeline, Instant instant) {
        return timeline.lifetime()
                .map(lifetime -> lifetime.from().isBefore(instant) && lifetime.end().isAfter(instant))
                .orElse(false);
    }

    // ------------------------------------------------------------------ references and attributes

    /** Finds the entity or relationship a write targets, or reports that there is none. */
    private Working resolve(Ref ref) {
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

    private Working resolveTemporaryId(TemporaryId temporaryId) {
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
    private Working loadEntity(EntityId id, boolean report) {
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
    private Working loadRelationship(RelationshipId id, boolean report) {
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

    /**
     * Resolves the names of values to attributes of the type, reporting names that are unknown and attributes that are
     * deprecated. A name given twice, or an alias together with the name, keeps the later value. Values are converted
     * to the value type of their attribute; a value that does not fit is kept as it is, for validation to report.
     */
    private Resolved resolveValues(String path, SubjectType type, Map<String, Value> values) {
        Map<AttributeId, Value> resolved = new LinkedHashMap<>();
        List<Attribute> attributes = new ArrayList<>();
        boolean unresolved = false;
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            Optional<Attribute> found = type.attribute(entry.getKey());
            if (found.isEmpty()) {
                unknownAttribute(path, type, entry.getKey());
                unresolved = true;
                continue;
            }
            Attribute attribute = found.get();
            if (attribute.deprecated()) {
                problems.add(Problems.of(ProblemCode.DEPRECATED_ATTRIBUTE, attributePath(path, attribute),
                        "The attribute " + attribute.name() + " of " + type.name() + " is deprecated, so a write may "
                        + "not set it.", "a write without the attribute " + attribute.name(),
                        "a value for " + attribute.name(), null));
            }
            resolved.put(attribute.id(), Conversions.convert(entry.getValue(), attribute.valueType()));
            if (!attributes.contains(attribute)) {
                attributes.add(attribute);
            }
        }
        return new Resolved(resolved, attributes, unresolved);
    }

    private void unknownAttribute(String path, SubjectType type, String name) {
        List<ClosestName.Candidate> candidates = type.attributes().stream()
                .flatMap(attribute -> ClosestName.candidates(attribute.name(), attribute.aliases()).stream())
                .toList();
        String names = type.attributes().stream().map(Attribute::name).collect(Collectors.joining(", "));
        problems.add(Problems.of(ProblemCode.UNKNOWN_ATTRIBUTE, path + "." + name, "The " + type.kind() + " type "
                + type.name() + " has no attribute '" + name + "'.", names.isEmpty() ? "an attribute of " + type.name()
                : "one of " + names, name, ClosestName.ofCandidates(name, candidates)
                        .map(closest -> "Did you mean '" + closest + "'?").orElse(null)));
    }

    private static String attributePath(String path, Attribute attribute) {
        return path + "." + attribute.name();
    }

    private static String path(Ref ref) {
        return switch (ref) {
            case TemporaryId temporaryId -> temporaryId.toString();
            case EntityId id -> id.value();
            case RelationshipId id -> id.value();
        };
    }

    /** What holds an ID in the store or in this transaction: {@code an entity} or {@code a relationship}; else null. */
    private String holder(String text) {
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

    private boolean inStore(String text) {
        return storedEntities.containsKey(new EntityId(text)) || storedRelationships.containsKey(new RelationshipId(text));
    }

    /** Draws an ID from the generator's copy that no entity or relationship has, also not one that a caller supplied. */
    private String drawId() {
        String id;
        do {
            id = generator.next();
        } while (holder(id) != null);
        return id;
    }

    // ------------------------------------------------------------------ conflict checks

    /**
     * Compares the entity or relationship as the caller read it with it as committed now. A per-attribute check
     * compares the given attributes; for a write that sets the complete state (a replace or an end-date),
     * {@code attributes} is {@code null} and the whole entity or relationship is compared. One created in this
     * transaction cannot have changed.
     */
    private void checkConflict(ConflictCheck check, Working target, String path, List<Attribute> attributes) {
        Basis readAt = switch (check) {
            case ConflictCheck.None none -> null;
            case ConflictCheck.PerEntity perEntity -> perEntity.readAt();
            case ConflictCheck.PerAttribute perAttribute -> perAttribute.readAt();
        };
        if (readAt == null || target.created) {
            return;
        }
        Stored<?> stored = target.stored;
        Timeline then = stored.at(readAt);
        Timeline now = stored.latest();
        boolean perAttribute = check instanceof ConflictCheck.PerAttribute && attributes != null;
        if (!perAttribute) {
            if (!then.sameState(now)) {
                problems.add(conflict(path, "The " + target.kind() + " was changed by a transaction after basis "
                        + readAt.value() + ", at which the write's caller read it.", readAt, stored, target.kind()));
            }
            return;
        }
        for (Attribute attribute : attributes) {
            if (!then.sameValues(now, attribute.id())) {
                problems.add(conflict(attributePath(path, attribute), "The attribute " + attribute.name()
                        + " was changed by a transaction after basis " + readAt.value() + ", at which the write's "
                        + "caller read it.", readAt, stored, target.kind()));
            }
        }
    }

    private static Problem conflict(String path, String message, Basis readAt, Stored<?> stored, String kind) {
        return Problems.of(ProblemCode.CONFLICT, path, message, "no change since basis " + readAt.value(),
                "a change recorded at basis " + stored.latestBasis().value(),
                "Read the " + kind + " again, and decide whether the write still applies.");
    }

    // ------------------------------------------------------------------ rules

    /**
     * Runs the rules of the entities the transaction writes (design D2 and D3 of {@code add-entity-rules}), after the
     * declarative checks of every entity. An entity takes part if it is written, has rules and has no problem so far;
     * one that is only the end of a relationship does not. A rule with code runs for the segments of its new timeline
     * that differ from its previous timeline, of which there may be none; a rule without code is reported whatever the
     * write changed. The rules read a provisional view of the state the transaction would produce, which is built once
     * and only if some entity takes part.
     */
    private void runRules() {
        List<RuleRunner.Subject> subjects = new ArrayList<>();
        for (Working entity : working.values()) {
            if (entity.isRelationship() || entity.failed() || !entity.written
                    || entity.entityType.effectiveRules().isEmpty() || hasProblems(entity)) {
                continue;
            }
            subjects.add(new RuleRunner.Subject((EntityId) entity.id, entity.path, entity.entityType,
                    entity.timeline.changedSince(entity.before)));
        }
        if (subjects.isEmpty()) {
            return;
        }
        Map<EntityId, ProvisionalView.Overlaid> overlay = new HashMap<>();
        for (Working entity : working.values()) {
            if (!entity.isRelationship() && !entity.failed()) {
                overlay.put((EntityId) entity.id, new ProvisionalView.Overlaid(entity.entityType.id(),
                        entity.timeline));
            }
        }
        problems.addAll(new RuleRunner(rules, new ProvisionalView.Source(commit.basis(), index, storedEntities,
                overlay), sequence).run(subjects));
    }

    /**
     * Tells whether a problem found so far is about the entity, one of its attributes, or one of its relationship
     * types, such as {@code $a3[HeldBy]}.
     */
    private boolean hasProblems(Working entity) {
        for (Problem problem : problems) {
            if (isAbout(problem.path(), entity.path) || isAbout(problem.path(), ((EntityId) entity.id).value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAbout(String problemPath, String entityPath) {
        return problemPath.equals(entityPath) || problemPath.startsWith(entityPath + ".")
                || problemPath.startsWith(entityPath + "[");
    }

    // ------------------------------------------------------------------ validation of the resulting state

    private void validate(Working subject) {
        SubjectType type = subject.type();
        for (Attribute attribute : type.attributes()) {
            String path = attributePath(subject.path, attribute);
            Set<Problem> valueProblems = new LinkedHashSet<>();
            for (Segment segment : subject.timeline.segments()) {
                Value value = segment.values().get(attribute.id());
                if (value != null) {
                    valueProblems.addAll(checks.check(path, attribute, value));
                }
            }
            problems.addAll(valueProblems);
            if (attribute.required() && !subject.namesUnresolved) {
                reportMissing(subject, attribute, path);
            }
        }
        for (Attribute attribute : type.attributes()) {
            if (attribute.unique()) {
                validateUnique(subject, attribute);
            }
        }
    }

    /** Reports each stretch of the subject's lifetime in which a required attribute has no value. */
    private void reportMissing(Working subject, Attribute attribute, String path) {
        Instant from = null;
        Instant end = null;
        for (Segment segment : subject.timeline.segments()) {
            if (segment.values().containsKey(attribute.id())) {
                continue;
            }
            if (from != null && end.equals(segment.from())) {
                end = segment.end();
            } else {
                if (from != null) {
                    missing(path, attribute, from, end);
                }
                from = segment.from();
                end = segment.end();
            }
        }
        if (from != null) {
            missing(path, attribute, from, end);
        }
    }

    private void missing(String path, Attribute attribute, Instant from, Instant end) {
        problems.add(Problems.of(ProblemCode.MISSING_REQUIRED_VALUE, path, "The required attribute "
                + attribute.name() + " has no value " + Segment.describe(from, end) + ".",
                "a " + Values.typeName(attribute.valueType()) + " value", "no value", null));
    }

    /**
     * Reports every other entity, or for a relationship every other relationship, that has a value of a unique
     * attribute at the same time as this one has the same value. The others are those of the store that this
     * transaction does not touch, and those it touches before this one, so that a pair of touched ones is reported once,
     * on the one that came second.
     */
    private void validateUnique(Working subject, Attribute attribute) {
        List<Segment> mine = subject.timeline.segments().stream()
                .filter(segment -> segment.values().containsKey(attribute.id()))
                .toList();
        if (mine.isEmpty()) {
            return;
        }
        Set<Id> reported = new HashSet<>();
        Collection<? extends Stored<?>> peers = subject.isRelationship() ? storedRelationships.values()
                : storedEntities.values();
        for (Stored<?> other : peers) {
            if (!working.containsKey(other.id())) {
                clash(subject, attribute, mine, other.id(), other.id().value(), other.latest(), reported);
            }
        }
        for (Working other : working.values()) {
            if (other == subject) {
                break;
            }
            if (!other.failed() && other.isRelationship() == subject.isRelationship()) {
                clash(subject, attribute, mine, other.id, other.path, other.timeline, reported);
            }
        }
    }

    private void clash(Working subject, Attribute attribute, List<Segment> mine, Id otherId, String otherPath,
            Timeline other, Set<Id> reported) {
        for (Segment theirs : other.segments()) {
            Value theirValue = theirs.values().get(attribute.id());
            if (theirValue == null) {
                continue;
            }
            for (Segment own : mine) {
                Value ownValue = own.values().get(attribute.id());
                Instant from = max(own.from(), theirs.from());
                Instant end = min(own.end(), theirs.end());
                if (from.isBefore(end) && Conversions.same(ownValue, theirValue) && reported.add(otherId)) {
                    String shown = Values.describe(ownValue);
                    problems.add(Problems.of(ProblemCode.UNIQUE_VALUE_TAKEN, attributePath(subject.path, attribute),
                            "The value '" + shown + "' of the unique attribute " + attribute.name() + " is also the "
                            + "value of " + otherPath + " " + Segment.describe(from, end) + ".",
                            "a value that no other " + subject.kind() + " has at the same time", shown,
                            "Use another value, or make the periods not overlap."));
                    return;
                }
            }
        }
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
