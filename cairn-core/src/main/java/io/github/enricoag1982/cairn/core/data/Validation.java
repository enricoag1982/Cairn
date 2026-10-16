package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.WriteChecks.attributePath;

import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the state that the writes leave an entity or a relationship in (design D5 and D6 of
 * {@code add-entity-versions}): the declarative constraints of its attributes in every segment of its timeline, the
 * required attributes in every stretch of its lifetime, and unique attributes against every other entity or
 * relationship; then the checks of its type (design D4 of {@code add-cel-expressions}), run by a {@link CheckRunner} on
 * every segment the transaction changes. The problems follow the order of the attributes in the schema, and then those
 * of the checks.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class Validation {

    private final Workspace workspace;
    private final ProblemLog problems;
    private final ValueChecks checks;
    private final CheckRunner checkRunner;

    Validation(Workspace workspace, ValueChecks checks) {
        this.workspace = workspace;
        this.problems = workspace.problems();
        this.checks = checks;
        this.checkRunner = new CheckRunner(workspace);
    }

    void validate(Working subject) {
        SubjectType type = subject.type();
        for (Attribute attribute : type.attributes()) {
            String path = attributePath(subject.path, attribute);
            Set<Problem> valueProblems = new LinkedHashSet<>();
            for (Segment segment : subject.timeline.segments()) {
                Value value = segment.values().get(attribute.id());
                if (value != null) {
                    valueProblems.addAll(checks.check(path, attribute, value, workspace.withheld(attribute)));
                }
            }
            problems.addAll(valueProblems);
            boolean derivedUnresolved = attribute.derived().isPresent() && subject.derivedUnresolved;
            if (attribute.required() && !subject.namesUnresolved && !derivedUnresolved) {
                reportMissing(subject, attribute, path);
            }
        }
        for (Attribute attribute : type.attributes()) {
            if (attribute.unique()) {
                validateUnique(subject, attribute);
            }
        }
        checkRunner.run(subject);
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
     * transaction does not touch, found through the index of unique values and taken in the order of their IDs, and those
     * it touches before this one, so that a pair of touched ones is reported once, on the one that came second.
     */
    private void validateUnique(Working subject, Attribute attribute) {
        List<Segment> mine = subject.timeline.segments().stream()
                .filter(segment -> segment.values().containsKey(attribute.id()))
                .toList();
        if (mine.isEmpty()) {
            return;
        }
        Set<Id> reported = new HashSet<>();
        Set<String> candidates = new TreeSet<>();
        for (Value value : new LinkedHashSet<>(mine.stream().map(segment -> segment.values().get(attribute.id()))
                .toList())) {
            candidates.addAll(workspace.ledger().uniqueIds(attribute.id(), value, Ledger.LATEST));
        }
        for (String text : candidates) {
            Id id = subject.isRelationship() ? new RelationshipId(text) : new EntityId(text);
            if (!workspace.working().containsKey(id)) {
                workspace.ledger().held(id).ifPresent(other -> clash(subject, attribute, mine, other.id(),
                        other.id().value(), other.timeline(), reported));
            }
        }
        for (Working other : workspace.working().values()) {
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
                    if (workspace.withheld(attribute)) {
                        // The value, the ID of the other and when it has the value would tell what the writer may not
                        // read (ADR-0036): the problem says that the attribute clashes, and nothing more.
                        problems.add(Problems.of(ProblemCode.UNIQUE_VALUE_TAKEN,
                                attributePath(subject.path, attribute), "The value of the unique attribute "
                                + attribute.name() + " is also the value of another " + subject.kind() + " at the same "
                                + "time.", "a value that no other " + subject.kind() + " has at the same time", null,
                                "Use another value, or make the periods not overlap."));
                        return;
                    }
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
