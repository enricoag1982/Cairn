package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the integrity of relationships over effective time, in the state that a transaction would produce (design D3
 * of {@code add-relationships}, ADR-0008, ADR-0010). Nothing is checked until the writes and the cascades of the
 * composition have been applied, so a transaction can end an entity and its relationships together, or move a
 * relationship from one entity to another.
 *
 * <p>The checks at every point in effective time work on intervals between consecutive boundaries, the starts and ends
 * of the timelines involved, inside which nothing changes:
 *
 * <ul>
 *   <li><b>Ends.</b> Both ends of a relationship exist throughout its lifetime. A relationship whose lifetime grew in
 *       the transaction is related outside the lifetimes of its ends ({@code END_NOT_EFFECTIVE}); one whose lifetime
 *       did not grow lasts longer than an end that was ended or shortened ({@code RELATIONSHIP_OUTLIVES_END}), which is
 *       also found for relationships the transaction does not touch.</li>
 *   <li><b>Multiplicities.</b> For every entity of a transaction and every relationship type with a multiplicity that
 *       can be broken, the number of relationships of the type and its subtypes on each interval is within the minimum
 *       and the maximum. A minimum of 1 applies only where the entity exists.</li>
 *   <li><b>Duplicates.</b> Two relationships of one type with the same ends, in either order for a symmetric type, do
 *       not overlap.</li>
 * </ul>
 *
 * <p>Each method reports the problems of one subject; the caller calls them in the order in which the subjects were
 * first touched. Used by one {@link Preparation}.
 */
final class Integrity {

    /** A relationship as the transaction would leave it. */
    private record Live(RelationshipId id, String path, RelationshipType type, EntityId source, EntityId target,
            Timeline timeline, Timeline before, boolean touched) {
    }

    /** Which end of a relationship an entity is, for the multiplicity that concerns it. */
    private enum Role { SOURCE, TARGET, EITHER }

    private final SchemaIndex index;
    private final Map<Id, Working> working;
    private final Ledger ledger;
    private final Set<RelationshipId> excluded;
    /** The relationships at each end that the transaction touches, then, once loaded, those of the store. */
    private final Map<EntityId, List<Live>> byEnd = new HashMap<>();
    /** The ends whose relationships in the store were loaded into {@link #byEnd}. */
    private final Set<EntityId> loaded = new HashSet<>();
    private final Map<RelationshipId, Live> byId = new HashMap<>();
    /** The relationships whose own problems were reported, so that a pair of duplicates is reported once. */
    private final Set<RelationshipId> processed = new HashSet<>();
    /** The relationships reported as outliving an end, so that a relationship with two ended ends is reported once. */
    private final Set<RelationshipId> reportedOutliving = new HashSet<>();

    /**
     * Prepares to check the relationships of the state the transaction would produce. The relationships that the
     * transaction touches are known at once; those of the store are read through the index of ends, for the entities
     * that are asked about, and only for them.
     *
     * @param index    the schema of the transaction
     * @param working  the entities and relationships that the transaction touches
     * @param ledger   the entities and relationships of the store
     * @param excluded relationships of the store to leave out as if they did not exist, which a purge removes
     */
    Integrity(SchemaIndex index, Map<Id, Working> working, Ledger ledger, Set<RelationshipId> excluded) {
        this.index = index;
        this.working = working;
        this.ledger = ledger;
        this.excluded = excluded;
        for (Working relationship : working.values()) {
            if (relationship.isRelationship() && !relationship.failed()) {
                add(new Live((RelationshipId) relationship.id, relationship.path, relationship.relationshipType,
                        relationship.source, relationship.target, relationship.timeline, relationship.before, true));
            }
        }
    }

    /**
     * The relationships that have an entity at an end, in the state the transaction would produce: those the transaction
     * touches in the order of the first touch, then those of the store that it does not touch, of types that the schema
     * has, in the order of their IDs.
     */
    private List<Live> relationshipsAt(EntityId end) {
        List<Live> found = byEnd.computeIfAbsent(end, key -> new ArrayList<>());
        if (loaded.add(end)) {
            Set<RelationshipId> seen = new TreeSet<>();
            for (Ledger.End entry : ledger.ends(end, Ledger.LATEST)) {
                seen.add(entry.relationship());
            }
            for (RelationshipId id : seen) {
                if (working.containsKey(id) || excluded.contains(id)) {
                    continue;
                }
                Live live = byId.get(id);
                if (live == null) {
                    live = ledger.relationship(id).flatMap(held -> index.relationshipType(held.type())
                            .map(type -> new Live(held.relationshipId(), held.id().value(), type, held.source(),
                                    held.target(), held.timeline(), held.timeline(), false))).orElse(null);
                    if (live != null) {
                        byId.put(id, live);
                    }
                }
                if (live != null) {
                    found.add(live);
                }
            }
        }
        return found;
    }

    private void add(Live relationship) {
        byId.put(relationship.id(), relationship);
        byEnd.computeIfAbsent(relationship.source(), key -> new ArrayList<>()).add(relationship);
        if (!relationship.target().equals(relationship.source())) {
            byEnd.computeIfAbsent(relationship.target(), key -> new ArrayList<>()).add(relationship);
        }
    }

    // ------------------------------------------------------------------ relationships

    /**
     * The problems of a relationship that the transaction touches: it lasts longer than an end, or an end does not
     * exist for the whole of it, and it duplicates another relationship.
     */
    List<Problem> relationshipProblems(Working relationship) {
        List<Problem> problems = new ArrayList<>();
        Live live = byId.get(relationship.id);
        if (live == null) {
            return problems;
        }
        List<String> missing = missingEnds(live);
        if (!missing.isEmpty()) {
            boolean grew = live.timeline().grewSince(live.before());
            problems.add(grew ? notEffective(live, missing) : outlives(live, missing));
            if (!grew) {
                reportedOutliving.add(live.id());
            }
        }
        duplicates(live, problems);
        processed.add(live.id());
        return problems;
    }

    /** Describes where each end of a relationship does not exist although the relationship does. */
    private List<String> missingEnds(Live relationship) {
        List<String> missing = new ArrayList<>();
        missing(relationship, "source", relationship.source(), missing);
        missing(relationship, "target", relationship.target(), missing);
        return missing;
    }

    private void missing(Live relationship, String role, EntityId end, List<String> missing) {
        for (Timeline.Stretch stretch : relationship.timeline().uncoveredBy(timelineOf(end))) {
            missing.add("its " + role + " " + pathOf(end) + " does not exist " + Segment.describe(stretch.from(),
                    stretch.end()));
        }
    }

    private Problem notEffective(Live relationship, List<String> missing) {
        return Problems.of(ProblemCode.END_NOT_EFFECTIVE, relationship.path(), "The " + relationship.type().name()
                + " relationship cannot exist where its ends do not: " + String.join(" and ", missing) + ".",
                "a relationship within the lifetimes of its source and target", lifetimeOf(relationship),
                "Shorten the period of the relationship, or create the entities for the period first.");
    }

    private Problem outlives(Live relationship, List<String> missing) {
        return Problems.of(ProblemCode.RELATIONSHIP_OUTLIVES_END, relationship.path(), "The "
                + relationship.type().name() + " relationship continues after one of its ends has ended: "
                + String.join(" and ", missing) + ".", "a relationship that ends no later than its source and target",
                lifetimeOf(relationship), "End the relationship in the same transaction, from the instant at which "
                + "the entity ends.");
    }

    private static String lifetimeOf(Live relationship) {
        return relationship.timeline().lifetime()
                .map(lifetime -> "a relationship " + Segment.describe(lifetime.from(), lifetime.end()))
                .orElse("a relationship that does not exist");
    }

    private void duplicates(Live relationship, List<Problem> problems) {
        for (Live other : relationshipsAt(relationship.source())) {
            if (other == relationship || !other.type().id().equals(relationship.type().id())
                    || (other.touched() && !processed.contains(other.id()))) {
                continue;
            }
            boolean sameEnds = other.source().equals(relationship.source())
                    && other.target().equals(relationship.target());
            boolean swapped = relationship.type().symmetric() && other.source().equals(relationship.target())
                    && other.target().equals(relationship.source());
            if (!sameEnds && !swapped) {
                continue;
            }
            Optional<Timeline.Stretch> overlap = relationship.timeline().firstOverlap(other.timeline());
            if (overlap.isPresent()) {
                problems.add(Problems.of(ProblemCode.DUPLICATE_RELATIONSHIP, relationship.path(), "The "
                        + relationship.type().name() + " relationship between " + pathOf(relationship.source())
                        + " and " + pathOf(relationship.target()) + " duplicates " + other.path() + " "
                        + Segment.describe(overlap.get().from(), overlap.get().end()) + ".",
                        "at most one " + relationship.type().name() + " relationship between the same entities at a "
                        + "time", "also " + other.path(), "Change the period so that the relationships do not "
                        + "overlap, or patch " + other.path() + " instead."));
            }
        }
    }

    // ------------------------------------------------------------------ entities

    /**
     * The problems of an entity that the transaction touches or relates: relationships that outlive it where its
     * lifetime shrank, and the multiplicities of every relationship type it is an end of.
     */
    List<Problem> entityProblems(Working entity) {
        List<Problem> problems = new ArrayList<>();
        EntityId id = (EntityId) entity.id;
        if (entity.timeline.shrankSince(entity.before)) {
            for (Live relationship : relationshipsAt(id)) {
                if (relationship.touched() || reportedOutliving.contains(relationship.id())) {
                    continue; // A relationship of the transaction is reported with its own problems.
                }
                List<String> missing = missingEnds(relationship);
                if (!missing.isEmpty()) {
                    reportedOutliving.add(relationship.id());
                    problems.add(outlives(relationship, missing));
                }
            }
        }
        multiplicities(entity, id, problems);
        return problems;
    }

    private void multiplicities(Working entity, EntityId id, List<Problem> problems) {
        Set<String> names = new HashSet<>(entity.entityType.supertypes());
        names.add(entity.entityType.name());
        for (RelationshipType type : index.schema().relationshipTypes()) {
            boolean asSource = names.contains(type.source());
            boolean asTarget = names.contains(type.target());
            if (type.symmetric()) {
                if (asSource) {
                    check(entity, id, type, Role.EITHER, type.targetsPerSource(), type.name(), problems);
                }
                continue;
            }
            if (asSource) {
                check(entity, id, type, Role.SOURCE, type.targetsPerSource(), type.name(), problems);
            }
            if (asTarget) {
                check(entity, id, type, Role.TARGET, type.sourcesPerTarget(), type.inverseName(), problems);
            }
        }
    }

    /**
     * Compares the number of relationships of a type, and of its subtypes, that an entity has at one end with the
     * multiplicity of that end, on each interval between consecutive boundaries. Adjacent intervals with the same
     * problem and the same count are reported as one.
     */
    private void check(Working entity, EntityId id, RelationshipType type, Role role, Multiplicity multiplicity,
            String label, List<Problem> problems) {
        if (multiplicity.minimum() == 0 && multiplicity.isUnbounded()) {
            return;
        }
        Set<TypeId> types = index.typeAndSubtypes(type);
        List<Live> related = relationshipsAt(id).stream()
                .filter(relationship -> types.contains(relationship.type().id()) && at(role, relationship, id))
                .toList();
        NavigableSet<Instant> boundaries = new TreeSet<>();
        boundariesOf(entity.timeline, boundaries);
        related.forEach(relationship -> boundariesOf(relationship.timeline(), boundaries));
        List<Instant> points = List.copyOf(boundaries);

        ProblemCode runCode = null;
        int runCount = 0;
        Instant runFrom = null;
        Instant runEnd = null;
        for (int i = 0; i + 1 < points.size(); i++) {
            Instant from = points.get(i);
            Instant end = points.get(i + 1);
            int count = (int) related.stream().filter(relationship -> relationship.timeline().at(from).isPresent())
                    .count();
            ProblemCode code = null;
            if (count > multiplicity.maximum()) {
                code = ProblemCode.TOO_MANY_RELATIONSHIPS;
            } else if (count < multiplicity.minimum() && !entity.linksUnresolved
                    && entity.timeline.at(from).isPresent()) {
                code = ProblemCode.TOO_FEW_RELATIONSHIPS;
            }
            if (code != null && code == runCode && count == runCount && runEnd.equals(from)) {
                runEnd = end;
                continue;
            }
            if (runCode != null) {
                problems.add(multiplicityProblem(entity, type, role, multiplicity, label, runCode, runCount, runFrom,
                        runEnd));
            }
            runCode = code;
            runCount = count;
            runFrom = from;
            runEnd = end;
        }
        if (runCode != null) {
            problems.add(multiplicityProblem(entity, type, role, multiplicity, label, runCode, runCount, runFrom,
                    runEnd));
        }
    }

    private static boolean at(Role role, Live relationship, EntityId id) {
        return switch (role) {
            case SOURCE -> relationship.source().equals(id);
            case TARGET -> relationship.target().equals(id);
            case EITHER -> relationship.source().equals(id) || relationship.target().equals(id);
        };
    }

    private static void boundariesOf(Timeline timeline, Set<Instant> boundaries) {
        for (Segment segment : timeline.segments()) {
            boundaries.add(segment.from());
            boundaries.add(segment.end());
        }
    }

    private static Problem multiplicityProblem(Working entity, RelationshipType type, Role role,
            Multiplicity multiplicity, String label, ProblemCode code, int count, Instant from, Instant end) {
        boolean tooFew = code == ProblemCode.TOO_FEW_RELATIONSHIPS;
        String relationships = type.name() + " relationship" + (count == 1 ? "" : "s")
                + (role == Role.TARGET ? " (as the target, navigated as '" + type.inverseName() + "')" : "");
        int bound = tooFew ? multiplicity.minimum() : multiplicity.maximum();
        return Problems.of(code, entity.path + "[" + label + "]", "The " + entity.entityType.name() + " " + entity.path
                + " has " + count + " " + relationships + " " + Segment.describe(from, end) + ", but "
                + (tooFew ? "needs at least " : "can have at most ") + bound + ".",
                (tooFew ? "at least " : "at most ") + bound + " " + type.name() + " relationship"
                + (bound == 1 ? "" : "s"), String.valueOf(count),
                tooFew ? "Relate " + entity.path + " with " + type.name() + " for the whole period, in the same "
                        + "transaction if it is new."
                        : "End a " + type.name() + " relationship before another one starts, or do not relate "
                        + entity.path + " again.");
    }

    // ------------------------------------------------------------------ the state

    private Timeline timelineOf(EntityId id) {
        Working touched = working.get(id);
        if (touched != null) {
            return touched.timeline;
        }
        return ledger.entity(id).map(Held::timeline).orElse(Timeline.EMPTY);
    }

    private String pathOf(EntityId id) {
        Working touched = working.get(id);
        return touched != null ? touched.path : id.value();
    }
}
