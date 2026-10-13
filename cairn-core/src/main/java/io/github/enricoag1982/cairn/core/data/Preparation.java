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
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
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
 * results (design D5 and D6 of {@code add-entity-versions}), and runs the rules that apply to it (design D3 of
 * {@code add-entity-rules}). Nothing in the store changes: a commit appends {@link #changes()} afterwards, and a dry
 * run drops them.
 *
 * <p>Writes are applied in order. A write with problems is applied as far as it can be, so that the problems of the
 * resulting state are found too: every problem of the transaction is reported, not only the first. Problems come in a
 * fixed order: those of the transaction itself, then those of each write in the order of the writes, then those of
 * each entity in the order in which the writes first touched it, and last those of the rules. Within an entity they
 * follow the order of the attributes in the schema.
 *
 * <p>A Preparation is used once and by one thread: the one holding the store's writer lock.
 */
final class Preparation {

    /** The path of problems about the transaction itself. */
    static final String TRANSACTION_PATH = "@transaction";

    /** The start of the path of problems about an entity that has neither a temporary ID nor a supplied ID. */
    private static final String WRITE_PATH = "@write.";

    /** What a commit records for an entity. */
    record Change(EntityId id, TypeId type, boolean created, Timeline timeline) {
    }

    /** An entity as this transaction changes it. A failed one stands for an entity that could not be created. */
    private static final class Working {
        final EntityId id;
        /**
         * What problems about the entity call it: its temporary ID if it was created with one, else its ID if the
         * caller supplied it, else {@code @write.<n>} for the write that creates it.
         */
        final String path;
        final EntityType entityType;
        final boolean created;
        final Timeline before;
        Timeline timeline;
        /** A write named an attribute that does not exist, so a required attribute may be missing under that name. */
        boolean namesUnresolved;

        Working(EntityId id, String path, EntityType entityType, boolean created, Timeline before, Timeline timeline) {
            this.id = id;
            this.path = path;
            this.entityType = entityType;
            this.created = created;
            this.before = before;
            this.timeline = timeline;
        }

        static Working failed(EntityId id, String path) {
            return new Working(id, path, null, true, Timeline.EMPTY, Timeline.EMPTY);
        }

        boolean failed() {
            return entityType == null;
        }
    }

    /** Values of a write resolved to attributes. */
    private record Resolved(Map<AttributeId, Value> values, List<Attribute> attributes, boolean unresolvedNames) {
    }

    private final Transaction transaction;
    private final SchemaIndex index;
    private final Map<EntityId, StoredEntity> stored;
    private final IdGenerator generator;
    private final CommitSequence.Commit commit;
    private final ValueChecks checks;
    private final Rules rules;
    private final CommitSequence sequence;

    private final List<Problem> problems = new ArrayList<>();
    private final Map<EntityId, Working> working = new LinkedHashMap<>();
    private final Map<TemporaryId, Working> temporaries = new HashMap<>();
    private final Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
    private final List<Id> created = new ArrayList<>();

    /**
     * Prepares a transaction.
     *
     * @param transaction the transaction
     * @param index       the schema the transaction is validated against
     * @param stored      the entities of the store, ordered by ID
     * @param generator   a copy of the store's ID generator, which this preparation advances
     * @param commit      the basis and transaction time the transaction would get
     * @param checks      the value checks
     * @param rules       the rules with code that run once the declarative checks have passed
     * @param sequence    the store's sequence, which the caller has locked for writing and which refuses writes while
     *                    the rules run
     */
    Preparation(Transaction transaction, SchemaIndex index, Map<EntityId, StoredEntity> stored,
            IdGenerator generator, CommitSequence.Commit commit, ValueChecks checks, Rules rules,
            CommitSequence sequence) {
        this.transaction = transaction;
        this.index = index;
        this.stored = stored;
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
                case Write.Patch patch -> patch(patch);
                case Write.Replace replace -> replace(replace);
                case Write.EndDate endDate -> endDate(endDate);
            }
        }
        for (Working entity : working.values()) {
            if (!entity.failed()) {
                validate(entity);
            }
        }
        runRules();
        return this;
    }

    List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** The ID of every entity named with a temporary ID, in the order of the writes. */
    Map<TemporaryId, Id> temporaryIds() {
        return temporaryIds;
    }

    /** The ID of every entity created, in the order of the writes. */
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
     * What a commit records: the entities that were created or whose state changed, in order of first touch. An entity
     * whose writes left it as it was, such as one that a transaction changes and changes back, records nothing, even
     * if the writes split its timeline into parts that different transactions recorded.
     */
    List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        for (Working entity : working.values()) {
            if (!entity.failed() && (entity.created || !entity.timeline.sameState(entity.before))) {
                changes.add(new Change(entity.id, entity.entityType.id(), entity.created, entity.timeline));
            }
        }
        return changes;
    }

    // ------------------------------------------------------------------ create

    /**
     * Applies a create. Problems about the new entity are located at its temporary ID, else at the ID the caller
     * supplied, else at {@code @write.<position>}: an ID that the store generates differs between a dry run and a
     * commit, so it cannot be the path of a problem that both report.
     */
    private void create(Write.Create write, int position) {
        Optional<TemporaryId> temporaryId = write.temporaryId();
        EntityId id = write.id().orElseGet(this::drawId);
        String path = temporaryId.map(TemporaryId::toString)
                .or(() -> write.id().map(EntityId::value))
                .orElse(WRITE_PATH + position);
        boolean ok = true;
        boolean idUsable = true;
        boolean duplicateTemporaryId = temporaryId.isPresent() && temporaries.containsKey(temporaryId.get());
        if (duplicateTemporaryId) {
            ok = false;
            problems.add(Problems.of(ProblemCode.DUPLICATE_TEMPORARY_ID, path, "The temporary ID " + temporaryId.get()
                    + " names two entities that this transaction creates.", "a temporary ID for each created entity",
                    temporaryId.get().toString(), null));
        }
        if (write.id().isPresent()) {
            if (!generator.accepts(id.value())) {
                ok = false;
                idUsable = false;
                problems.add(Problems.of(ProblemCode.INVALID_ENTITY_ID, path, "The supplied ID '" + id.value()
                        + "' is not in the format of the store's ID generator.",
                        "an ID in the format of the store's ID generator", id.value(),
                        "Leave the ID out to let the store generate one."));
            } else if (exists(id)) {
                ok = false;
                idUsable = false;
                problems.add(Problems.of(ProblemCode.ENTITY_EXISTS, path, "An entity with the ID " + id.value()
                        + (stored.containsKey(id) ? " exists already." : " is created twice in this transaction."),
                        "an ID that no entity has", id.value(), "Leave the ID out to let the store generate one."));
            }
        }
        EffectivePeriod period = write.period();
        if (!checkPeriod(path, period)) {
            ok = false;
        }
        EntityType type = entityType(path, write.entityType());
        Resolved resolved = null;
        if (type != null) {
            resolved = resolveValues(path, type, write.values());
        } else {
            ok = false;
        }
        Working entity;
        if (ok) {
            Timeline timeline = Timeline.created(period.from(), Segment.endOf(period), resolved.values(),
                    commit.transactionTime(), commit.basis());
            entity = new Working(id, path, type, true, Timeline.EMPTY, timeline);
            entity.namesUnresolved = resolved.unresolvedNames();
            created.add(id);
            temporaryId.ifPresent(temporary -> temporaryIds.put(temporary, id));
        } else {
            entity = Working.failed(id, path);
        }
        if (temporaryId.isPresent() && !duplicateTemporaryId) {
            temporaries.put(temporaryId.get(), entity);
        }
        if (idUsable) {
            working.put(id, entity);
        }
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

    // ------------------------------------------------------------------ patch, replace, end-date

    private void patch(Write.Patch write) {
        String path = path(write.target());
        Working target = resolve(write.target());
        boolean validPeriod = checkPeriod(path, write.period());
        if (target == null || target.failed()) {
            return;
        }
        Resolved set = resolveValues(path, target.entityType, write.set());
        Set<AttributeId> cleared = new LinkedHashSet<>();
        List<Attribute> affected = new ArrayList<>(set.attributes());
        boolean unresolved = set.unresolvedNames();
        for (String name : write.clear()) {
            Optional<Attribute> attribute = target.entityType.attribute(name);
            if (attribute.isEmpty()) {
                unknownAttribute(path, target.entityType, name);
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
        Resolved values = resolveValues(path, target.entityType, write.values());
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
            problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The entity cannot be ended from "
                    + write.from() + (lifetime.isPresent() ? ", because it exists only from "
                    + lifetime.get().from() + " on." : "."), "an instant after the start of the entity",
                    write.from().toString(), null));
            return;
        }
        target.timeline = target.timeline.endAt(write.from());
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

    /** Reports a period that the entity does not exist throughout; tells whether the entity exists throughout it. */
    private boolean isEffective(Working target, String path, EffectivePeriod period) {
        if (target.timeline.covers(period.from(), Segment.endOf(period))) {
            return true;
        }
        String lifetime = target.timeline.lifetime()
                .map(found -> "exists " + describe(found.from(), found.end()))
                .orElse("does not exist");
        problems.add(Problems.of(ProblemCode.ENTITY_NOT_EFFECTIVE, path, "The entity " + lifetime
                + ", so it cannot be changed " + describe(period.from(), Segment.endOf(period)) + ".",
                "a period within the lifetime of the entity", describe(period.from(), Segment.endOf(period)), null));
        return false;
    }

    // ------------------------------------------------------------------ references and attributes

    /** Finds the entity a write refers to, or reports that there is none. */
    private Working resolve(Ref ref) {
        return switch (ref) {
            case TemporaryId temporaryId -> resolveTemporaryId(temporaryId);
            case EntityId id -> resolveId(id);
        };
    }

    private Working resolveTemporaryId(TemporaryId temporaryId) {
        Working found = temporaries.get(temporaryId);
        if (found == null) {
            problems.add(Problems.of(ProblemCode.UNKNOWN_TEMPORARY_ID, temporaryId.toString(), "The temporary ID "
                    + temporaryId + " is not created by an earlier write of this transaction.",
                    "a temporary ID that an earlier create of this transaction names", temporaryId.toString(), null));
        }
        return found;
    }

    private Working resolveId(EntityId id) {
        Working found = working.get(id);
        if (found != null) {
            return found;
        }
        StoredEntity entity = stored.get(id);
        if (entity == null) {
            problems.add(Problems.of(ProblemCode.UNKNOWN_ENTITY, id.value(), "No entity has the ID " + id.value()
                    + ".", "the ID of an entity of the store", id.value(), null));
            return null;
        }
        Timeline latest = entity.latest();
        Working existing = new Working(id, id.value(), index.entityType(entity.type()).orElse(null), false, latest,
                latest);
        working.put(id, existing);
        return existing;
    }

    /**
     * Resolves the names of values to attributes of the type, reporting names that are unknown and attributes that are
     * deprecated. A name given twice, or an alias together with the name, keeps the later value. Values are converted
     * to the value type of their attribute; a value that does not fit is kept as it is, for validation to report.
     */
    private Resolved resolveValues(String path, EntityType type, Map<String, Value> values) {
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

    private void unknownAttribute(String path, EntityType type, String name) {
        List<ClosestName.Candidate> candidates = type.attributes().stream()
                .flatMap(attribute -> ClosestName.candidates(attribute.name(), attribute.aliases()).stream())
                .toList();
        String names = type.attributes().stream().map(Attribute::name).collect(Collectors.joining(", "));
        problems.add(Problems.of(ProblemCode.UNKNOWN_ATTRIBUTE, path + "." + name, "The entity type " + type.name()
                + " has no attribute '" + name + "'.", names.isEmpty() ? "an attribute of " + type.name()
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
        };
    }

    private boolean exists(EntityId id) {
        return working.containsKey(id) || stored.containsKey(id);
    }

    /** Draws an ID from the generator's copy that no entity has, also not one that a caller supplied. */
    private EntityId drawId() {
        EntityId id;
        do {
            id = generator.next();
        } while (exists(id));
        return id;
    }

    // ------------------------------------------------------------------ conflict checks

    /**
     * Compares the entity as the caller read it with the entity as committed now. A per-attribute check compares the
     * given attributes; for a write that sets the complete state (a replace or an end-date), {@code attributes} is
     * {@code null} and the whole entity is compared. An entity created in this transaction cannot have changed.
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
        StoredEntity entity = stored.get(target.id);
        Timeline then = entity.at(readAt);
        Timeline now = entity.latest();
        boolean perAttribute = check instanceof ConflictCheck.PerAttribute && attributes != null;
        if (!perAttribute) {
            if (!then.sameState(now)) {
                problems.add(conflict(path, "The entity was changed by a transaction after basis " + readAt.value()
                        + ", at which the write's caller read it.", readAt, entity));
            }
            return;
        }
        for (Attribute attribute : attributes) {
            if (!then.sameValues(now, attribute.id())) {
                problems.add(conflict(attributePath(path, attribute), "The attribute " + attribute.name()
                        + " was changed by a transaction after basis " + readAt.value() + ", at which the write's "
                        + "caller read it.", readAt, entity));
            }
        }
    }

    private static Problem conflict(String path, String message, Basis readAt, StoredEntity entity) {
        return Problems.of(ProblemCode.CONFLICT, path, message, "no change since basis " + readAt.value(),
                "a change recorded at basis " + entity.latestBasis().value(),
                "Read the entity again, and decide whether the write still applies.");
    }

    // ------------------------------------------------------------------ rules

    /**
     * Runs the rules of the entities the transaction writes (design D2 and D3 of {@code add-entity-rules}), after the
     * declarative checks of every entity. An entity takes part if it has rules and no problem so far. A rule with code
     * runs for the segments of its new timeline that differ from its previous timeline, of which there may be none; a
     * rule without code is reported whatever the write changed. The rules read a provisional view of the state the
     * transaction would produce, which is built once and only if some entity takes part.
     */
    private void runRules() {
        List<RuleRunner.Subject> subjects = new ArrayList<>();
        for (Working entity : working.values()) {
            if (entity.failed() || entity.entityType.effectiveRules().isEmpty() || hasProblems(entity)) {
                continue;
            }
            subjects.add(new RuleRunner.Subject(entity.id, entity.path, entity.entityType,
                    entity.timeline.changedSince(entity.before)));
        }
        if (subjects.isEmpty()) {
            return;
        }
        Map<EntityId, ProvisionalView.Overlaid> overlay = new HashMap<>();
        for (Working entity : working.values()) {
            if (!entity.failed()) {
                overlay.put(entity.id, new ProvisionalView.Overlaid(entity.entityType.id(), entity.timeline));
            }
        }
        problems.addAll(new RuleRunner(rules, new ProvisionalView.Source(commit.basis(), index, stored, overlay),
                sequence).run(subjects));
    }

    /** Tells whether a problem found so far is about the entity or one of its attributes. */
    private boolean hasProblems(Working entity) {
        for (Problem problem : problems) {
            if (isAbout(problem.path(), entity.path) || isAbout(problem.path(), entity.id.value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAbout(String problemPath, String entityPath) {
        return problemPath.equals(entityPath) || problemPath.startsWith(entityPath + ".");
    }

    // ------------------------------------------------------------------ validation of the resulting state

    private void validate(Working entity) {
        for (Attribute attribute : entity.entityType.attributes()) {
            String path = attributePath(entity.path, attribute);
            Set<Problem> valueProblems = new LinkedHashSet<>();
            for (Segment segment : entity.timeline.segments()) {
                Value value = segment.values().get(attribute.id());
                if (value != null) {
                    valueProblems.addAll(checks.check(path, attribute, value));
                }
            }
            problems.addAll(valueProblems);
            if (attribute.required() && !entity.namesUnresolved) {
                reportMissing(entity, attribute, path);
            }
        }
        for (Attribute attribute : entity.entityType.attributes()) {
            if (attribute.unique()) {
                validateUnique(entity, attribute);
            }
        }
    }

    /** Reports each stretch of the entity's lifetime in which a required attribute has no value. */
    private void reportMissing(Working entity, Attribute attribute, String path) {
        Instant from = null;
        Instant end = null;
        for (Segment segment : entity.timeline.segments()) {
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
                + attribute.name() + " has no value " + describe(from, end) + ".",
                "a " + Values.typeName(attribute.valueType()) + " value", "no value", null));
    }

    /**
     * Reports every other entity that has a value of a unique attribute at the same time as this entity has the same
     * value. The others are the entities of the store that this transaction does not touch, and the entities it touches
     * before this one, so that a pair of touched entities is reported once, on the entity that came second.
     */
    private void validateUnique(Working entity, Attribute attribute) {
        List<Segment> mine = entity.timeline.segments().stream()
                .filter(segment -> segment.values().containsKey(attribute.id()))
                .toList();
        if (mine.isEmpty()) {
            return;
        }
        Set<EntityId> reported = new HashSet<>();
        for (StoredEntity other : stored.values()) {
            if (!working.containsKey(other.id())) {
                clash(entity, attribute, mine, other.id(), other.id().value(), other.latest(), reported);
            }
        }
        for (Working other : working.values()) {
            if (other == entity) {
                break;
            }
            if (!other.failed()) {
                clash(entity, attribute, mine, other.id, other.path, other.timeline, reported);
            }
        }
    }

    private void clash(Working entity, Attribute attribute, List<Segment> mine, EntityId otherId, String otherPath,
            Timeline other, Set<EntityId> reported) {
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
                    problems.add(Problems.of(ProblemCode.UNIQUE_VALUE_TAKEN, attributePath(entity.path, attribute),
                            "The value '" + shown + "' of the unique attribute " + attribute.name() + " is also the "
                            + "value of " + otherPath + " " + describe(from, end) + ".",
                            "a value that no other entity has at the same time", shown,
                            "Use another value, or make the periods not overlap."));
                    return;
                }
            }
        }
    }

    /** Describes a stretch of effective time, such as {@code from 2026-01-01T00:00:00Z until further notice}. */
    private static String describe(Instant from, Instant end) {
        return "from " + from + (end.equals(Segment.OPEN) ? " until further notice" : " until " + end);
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
