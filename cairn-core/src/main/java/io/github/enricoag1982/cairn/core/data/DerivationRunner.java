package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.WriteChecks.attributePath;

import io.github.enricoag1982.cairn.core.schema.AppliedDerivation;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Computes the derived attributes of an entity or a relationship that a transaction writes (design D4 of
 * {@code add-derived-attributes}, capability {@code derived-attributes}): for every segment of the subject's timeline that
 * the transaction changes, each derived attribute of its type is computed from the segment's other values, which are
 * those that are not derived, and replaces whatever the segment holds for it, or leaves the segment without a value if
 * the expression gives none. The values are then part of the version, so value constraints, uniqueness, checks and rules
 * see them like any other value, and the ledger stores them.
 *
 * <p>The segments to compute are those that {@link Timeline#changedSince} selects, as for checks: a segment that a
 * cascade only cuts, or that a write leaves with the values it had, keeps its stored derived values. A creation and a
 * replace give no derived values, because a write cannot set them; they are computed here.
 *
 * <p>Nothing is computed for a subject if a write named one of its derived attributes (that write is a problem of its
 * own), or if a value of a segment does not fit its attribute, which has its own problem. A derived value that cannot be
 * computed is a {@code DERIVATION_FAILED} at the attribute, and a store that has no expression language for a type with
 * derived attributes rejects the write with one {@code EXPRESSIONS_UNAVAILABLE}, whatever the write changes, as for
 * checks. The subject is then marked as {@linkplain Working#derivedUnresolved having unresolved derived values}.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class DerivationRunner {

    private final Workspace workspace;
    private final ProblemLog problems;

    DerivationRunner(Workspace workspace) {
        this.workspace = workspace;
        this.problems = workspace.problems();
    }

    /** Computes the derived attributes of a written subject. */
    void run(Working subject) {
        SubjectType type = subject.type();
        List<Attribute> derived = type.attributes().stream().filter(attribute -> attribute.derived().isPresent()).toList();
        if (derived.isEmpty()) {
            return;
        }
        List<AppliedDerivation> applied = workspace.index().derivations(type.id());
        if (applied.size() != derived.size() || applied.stream().anyMatch(AppliedDerivation::unavailable)) {
            problems.add(unavailable(subject, type, derived));
            subject.derivedUnresolved = true;
            return;
        }
        if (subject.derivedUnresolved) {
            return;
        }
        List<Segment> changed = subject.timeline.changedSince(subject.before);
        Map<Segment, Segment> replaced = new HashMap<>();
        for (Segment segment : changed) {
            Optional<Map<String, Value>> inputs = inputs(type, segment);
            if (inputs.isEmpty()) {
                subject.derivedUnresolved = true;
                continue;
            }
            String version = changed.size() > 1 ? " in the version from " + segment.from() : "";
            Map<AttributeId, Value> values = new HashMap<>(segment.values());
            boolean complete = true;
            for (AppliedDerivation derivation : applied) {
                Attribute attribute = derivation.attribute();
                values.remove(attribute.id());
                Optional<Value> value = compute(subject, derivation, inputs.get(), version);
                if (value == null) {
                    complete = false;
                } else {
                    value.ifPresent(present -> values.put(attribute.id(),
                            Conversions.convert(present, attribute.valueType())));
                }
            }
            if (complete) {
                replaced.put(segment, new Segment(segment.from(), segment.end(), values, segment.recordedAt(),
                        segment.recordedBasis()));
            } else {
                subject.derivedUnresolved = true;
            }
        }
        if (!replaced.isEmpty()) {
            List<Segment> segments = new ArrayList<>();
            for (Segment segment : subject.timeline.segments()) {
                segments.add(replaced.getOrDefault(segment, segment));
            }
            subject.timeline = Timeline.of(segments);
        }
    }

    /**
     * Computes one derived value for one segment.
     *
     * @return the value, or empty if the expression gives none for an optional attribute; {@code null} if a problem was
     *         reported
     */
    private Optional<Value> compute(Working subject, AppliedDerivation applied, Map<String, Value> inputs, String version) {
        Attribute attribute = applied.attribute();
        String path = attributePath(subject.path, attribute);
        if (applied.compiled().isEmpty()) {
            problems.add(notCompiled(path, attribute, applied.failure().orElse("it was not compiled")));
            return null;
        }
        Optional<Value> value;
        try {
            value = applied.compiled().get().evaluate(inputs);
        } catch (ExpressionEvaluationException | RuntimeException | StackOverflowError e) {
            problems.add(failed(path, attribute, version, e.getMessage() != null ? e.getMessage() : e.toString()));
            return null;
        }
        if (value.isPresent() && !Values.fits(value.get(), attribute.valueType())) {
            problems.add(failed(path, attribute, version, "The result " + Values.describe(value.get()) + " is "
                    + ValueChecks.withArticle(value.get().valueType()) + ", which does not fit the "
                    + Values.typeName(attribute.valueType()) + " attribute."));
            return null;
        }
        if (value.isEmpty() && attribute.required()) {
            problems.add(failed(path, attribute, version, "The expression gave no value, but the attribute is "
                    + "required."));
            return null;
        }
        return value;
    }

    /**
     * The values of a segment that are not derived, by attribute name, or empty if one of them does not fit its attribute:
     * the value has its own problem, and an expression cannot be asked about values it was not compiled for.
     */
    private static Optional<Map<String, Value>> inputs(SubjectType type, Segment segment) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (Attribute attribute : type.attributes()) {
            Value value = segment.values().get(attribute.id());
            if (value == null || attribute.derived().isPresent()) {
                continue;
            }
            if (!Values.fits(value, attribute.valueType())) {
                return Optional.empty();
            }
            values.put(attribute.name(), value);
        }
        return Optional.of(values);
    }

    // ------------------------------------------------------------------ problems

    private static Problem failed(String path, Attribute attribute, String version, String reason) {
        return Problems.of(ProblemCode.DERIVATION_FAILED, path, "The derivation of the attribute " + attribute.name()
                + " gave no value that fits it" + version + ": " + reason,
                "a value that fits the " + Values.typeName(attribute.valueType()) + " attribute " + attribute.name()
                        + (attribute.required() ? "" : ", or none"), reason,
                "Test an optional attribute with has(self.<name>) before reading it, or correct the expression in a "
                + "new version of the schema.");
    }

    private static Problem notCompiled(String path, Attribute attribute, String reason) {
        return Problems.of(ProblemCode.DERIVATION_FAILED, path, "The derivation of the attribute " + attribute.name()
                + " of " + attribute.declaredBy() + " gave no value because it could not be compiled when the schema was "
                + "loaded: " + reason, "an expression that compiles", reason,
                "Correct the expression in a new version of the schema.");
    }

    private static Problem unavailable(Working subject, SubjectType type, List<Attribute> derived) {
        String names = derived.stream().map(Attribute::name).collect(Collectors.joining(", "));
        return Problems.of(ProblemCode.EXPRESSIONS_UNAVAILABLE, subject.path, "The " + subject.kind() + " is of type "
                + type.name() + ", which has the derived attributes " + names + ", but the store has "
                + "no expression language to compute them, so it cannot be written.", "a store with an expression "
                + "language", "no expression language", "Put the module cairn-cel on the class path, or create the "
                + "store with settings that have an expression language.");
    }
}
