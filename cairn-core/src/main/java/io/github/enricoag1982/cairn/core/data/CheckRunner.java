package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.AppliedCheck;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.expressions.CheckEvaluationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs the checks that apply to the type of an entity or a relationship that a transaction writes (design D4 of
 * {@code add-cel-expressions}, capability {@code checks}): each check, in the order of the type's effective checks, for
 * every segment of the subject's timeline that the transaction changes, with the values of that segment. A check that
 * is false is a {@code CHECK_VIOLATED}, and one that gives no result a {@code CHECK_FAILED}.
 *
 * <p>The checks read nothing but the segment they check, so they cause no re-checks. A store that has no expression
 * language for the checks of a type it writes to rejects the write with one {@code CHECKS_UNAVAILABLE}, whatever the
 * write changes, as a rule without code does (ADR-0018).
 *
 * <p>A segment with a value that does not fit its attribute's value type is not checked: the value has its own problem,
 * and an expression cannot be asked about values it was not compiled for.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class CheckRunner {

    private final Workspace workspace;
    private final ProblemLog problems;

    CheckRunner(Workspace workspace) {
        this.workspace = workspace;
        this.problems = workspace.problems();
    }

    /** Runs the checks of a written subject. */
    void run(Working subject) {
        SubjectType type = subject.type();
        List<EffectiveCheck> effective = type.effectiveChecks();
        if (effective.isEmpty()) {
            return;
        }
        List<AppliedCheck> applied = workspace.index().checks(type.id());
        if (applied.size() != effective.size() || applied.stream().anyMatch(AppliedCheck::unavailable)) {
            problems.add(unavailable(subject, type, effective));
            return;
        }
        List<Segment> changed = subject.timeline.changedSince(subject.before);
        for (AppliedCheck check : applied) {
            if (check.compiled().isEmpty()) {
                problems.add(notCompiled(subject, check));
                continue;
            }
            runCheck(subject, type, check, check.compiled().get(), changed);
        }
    }

    private void runCheck(Working subject, SubjectType type, AppliedCheck applied, CompiledCheck compiled,
            List<Segment> changed) {
        Check check = applied.check().check();
        for (Segment segment : changed) {
            Optional<Map<String, Value>> values = values(type, segment);
            if (values.isEmpty()) {
                continue;
            }
            String version = changed.size() > 1 ? " in the version from " + segment.from() : "";
            try {
                if (!compiled.evaluate(values.get())) {
                    problems.add(violated(subject, check, version));
                }
            } catch (CheckEvaluationException | RuntimeException | StackOverflowError e) {
                problems.add(failed(subject, check, version, e));
            }
        }
    }

    /** The values of a segment by attribute name, or empty if one of them does not fit its attribute. */
    private static Optional<Map<String, Value>> values(SubjectType type, Segment segment) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (Attribute attribute : type.attributes()) {
            Value value = segment.values().get(attribute.id());
            if (value == null) {
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

    private static Problem violated(Working subject, Check check, String version) {
        String text = check.message().orElse("the expression " + check.expression() + " is false.");
        return Problems.of(ProblemCode.CHECK_VIOLATED, subject.path, "The check " + check.name() + " is violated"
                + version + ": " + text, "a version for which " + check.expression() + " is true",
                null, null);
    }

    private static Problem failed(Working subject, Check check, String version, Throwable error) {
        String reason = error.getMessage() != null ? error.getMessage() : error.toString();
        return Problems.of(ProblemCode.CHECK_FAILED, subject.path, "The check " + check.name() + " gave no result"
                + version + ": " + reason, "a result that is true or false", reason,
                "Test an optional attribute with has(self.<name>) before reading it, or correct the expression in a "
                + "new version of the schema.");
    }

    private static Problem notCompiled(Working subject, AppliedCheck applied) {
        Check check = applied.check().check();
        String reason = applied.failure().orElse("it was not compiled");
        return Problems.of(ProblemCode.CHECK_FAILED, subject.path, "The check " + check.name() + " of "
                + applied.check().declaredBy() + " gave no result because it could not be compiled when the schema was "
                + "loaded: " + reason, "a check that compiles", reason,
                "Correct the expression in a new version of the schema.");
    }

    private static Problem unavailable(Working subject, SubjectType type, List<EffectiveCheck> effective) {
        String names = effective.stream().map(check -> check.check().name()).collect(Collectors.joining(", "));
        return Problems.of(ProblemCode.CHECKS_UNAVAILABLE, subject.path, "The " + subject.kind() + " is of type "
                + type.name() + ", to which the checks " + names + " apply, but the store has no expression language "
                + "to run them, so it cannot be written.", "a store with an expression language",
                "no expression language", "Put the module cairn-cel on the class path, or create the "
                + "store with settings that have an expression language.");
    }
}
