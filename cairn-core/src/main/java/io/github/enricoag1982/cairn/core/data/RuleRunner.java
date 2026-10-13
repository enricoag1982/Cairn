package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs the rules that apply to the entities and relationships a transaction writes, and turns what they report into
 * problems (design D2, D3 and D4 of {@code add-entity-rules} and D5 of {@code add-relationships}). It runs in the writer
 * thread, one rule after another, so a slow rule slows the commit. While it runs, the store refuses writes, so a rule
 * cannot commit, run dry or register a schema; a rule that tries is reported as failed, also if it catches the refusal
 * and returns.
 *
 * <p>For each subject, an entity or a relationship, it takes the effective rules of the subject's type, in the order of
 * the schema, and runs each one for each changed segment, in the order of the timeline. A rule that the store has no
 * code for is reported for the subject whatever the write changed. A rule must fit the kind of the subject's type: an
 * {@link EntityRule} checks entities, and a {@link RelationshipRule} checks relationships; any other rule that a type
 * names is reported as failed. The problems come in that order, subject by subject.
 */
final class RuleRunner {

    /**
     * An entity or a relationship whose rules run.
     *
     * @param id      the ID of the entity or relationship
     * @param path    what problems about it call it
     * @param type    its type in the schema the transaction would produce
     * @param source  the source of a relationship; null for an entity
     * @param target  the target of a relationship; null for an entity
     * @param changed the segments of its new timeline that differ from its previous timeline; none if the transaction
     *                writes it without changing its values
     */
    record Subject(Id id, String path, SubjectType type, EntityId source, EntityId target, List<Segment> changed) {

        /** The rule interface that checks subjects of this kind. */
        String ruleInterface() {
            return type.relationship() ? "RelationshipRule" : "EntityRule";
        }

        /** Tells whether a rule can check subjects of this kind. */
        boolean fits(Rule rule) {
            return type.relationship() ? rule instanceof RelationshipRule : rule instanceof EntityRule;
        }
    }

    /** A problem that a rule reported through its context. */
    private record Reported(String code, String attribute, String message) {
    }

    private final Rules rules;
    private final ProvisionalView.Source source;
    private final CommitSequence sequence;

    /**
     * Creates a runner for one transaction.
     *
     * @param rules    the rules the store has code for
     * @param source   the state the rules read
     * @param sequence the sequence of the store, which refuses writes while the rules run
     */
    RuleRunner(Rules rules, ProvisionalView.Source source, CommitSequence sequence) {
        this.rules = rules;
        this.source = source;
        this.sequence = sequence;
    }

    /**
     * Runs the rules of the subjects. A rule that fails for one segment of a subject is not run for its other segments,
     * and a problem that a rule reports for several segments is reported once. Must be called with the writer lock
     * held: while the rules run, the store refuses commits, dry runs and schema registrations with an
     * {@code IllegalStateException}, which is a failure of the rule that caused it, whether the rule lets the exception
     * go or catches it.
     *
     * @param subjects the entities and relationships whose rules run
     * @return the problems, none if every rule is content
     * @throws VirtualMachineError if a rule causes one, such as an {@code OutOfMemoryError}; it is not turned into a
     *                             problem
     */
    List<Problem> run(List<Subject> subjects) {
        return sequence.whileRunningRules(() -> runAll(subjects));
    }

    private List<Problem> runAll(List<Subject> subjects) {
        List<Problem> problems = new ArrayList<>();
        for (Subject subject : subjects) {
            Set<Problem> found = new LinkedHashSet<>();
            for (EffectiveRule effective : subject.type().effectiveRules()) {
                Optional<Rules.Registered> registered = rules.find(effective.rule());
                if (registered.isEmpty()) {
                    found.add(unavailable(subject, effective));
                    continue;
                }
                if (subject.changed().isEmpty()) {
                    continue; // The rule has code, and a write that changes no values runs no rules.
                }
                if (!subject.fits(registered.get().rule())) {
                    found.add(notForKind(subject, registered.get()));
                    continue;
                }
                for (Segment segment : subject.changed()) {
                    if (!check(subject, registered.get(), segment, found)) {
                        break;
                    }
                }
            }
            problems.addAll(found);
        }
        return problems;
    }

    /**
     * Runs a rule for one segment; tells whether it ran to its end. Anything the rule throws is a failure of the rule,
     * except a {@link VirtualMachineError}: when the virtual machine is out of resources, nothing can be said about the
     * rule, so the error goes on. A rule that tried to write has failed too, even if it caught the refusal and returned:
     * the sequence counts the refused writes, and if the count grew while the rule ran, what the rule reported is
     * dropped, as for an exception.
     */
    private boolean check(Subject subject, Rules.Registered registered, Segment segment, Set<Problem> problems) {
        Consumer<RuleContext> call = call(subject, registered.rule(), segment);
        Context context = new Context(source, segment.from());
        long refusedBefore = sequence.refusedWrites();
        try {
            call.accept(context);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            problems.add(threw(subject, registered.descriptor(), e));
            return false;
        } finally {
            context.close();
        }
        if (sequence.refusedWrites() != refusedBefore) {
            problems.add(wrote(subject, registered.descriptor()));
            return false;
        }
        for (Reported reported : context.reported) {
            problems.add(problem(subject, registered, reported));
        }
        return true;
    }

    /** The call of a rule that fits the subject, with the version of the subject that a segment holds. */
    private Consumer<RuleContext> call(Subject subject, Rule rule, Segment segment) {
        if (subject.type().relationship()) {
            RelationshipState version = RelationshipStates.of(source.index(), (RelationshipId) subject.id(),
                    subject.type().id(), subject.source(), subject.target(), segment).orElseThrow();
            RelationshipRule relationshipRule = (RelationshipRule) rule;
            return context -> relationshipRule.check(version, context);
        }
        EntityState version = EntityStates.of(source.index(), (EntityId) subject.id(), subject.type().id(), segment)
                .orElseThrow();
        EntityRule entityRule = (EntityRule) rule;
        return context -> entityRule.check(version, context);
    }

    /** What a rule reported: a violation, or a failure of the rule if it names a code or an attribute wrongly. */
    private static Problem problem(Subject subject, Rules.Registered registered, Reported reported) {
        if (!registered.codes().contains(reported.code())) {
            return undeclared(subject, registered, reported);
        }
        if (reported.attribute() == null) {
            return violation(registered.descriptor(), reported, subject.path());
        }
        Optional<Attribute> attribute = subject.type().attribute(reported.attribute());
        return attribute.isPresent()
                ? violation(registered.descriptor(), reported, subject.path() + "." + attribute.get().name())
                : unknownAttribute(subject, registered.descriptor(), reported);
    }

    // ------------------------------------------------------------------ problems

    private static Problem violation(RuleDescriptor rule, Reported reported, String path) {
        return new Problem(ProblemCode.RULE_VIOLATION, path, reported.message(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(rule.name() + "/" + reported.code()));
    }

    private static Problem threw(Subject subject, RuleDescriptor rule, Throwable e) {
        String message = e.getMessage();
        return Problems.of(ProblemCode.RULE_FAILED, subject.path(), "The rule " + name(rule) + " threw "
                + e.getClass().getName() + " while checking " + subject.path() + ".",
                "a rule that checks without an exception", message != null ? message : e.getClass().getName(),
                "Correct the rule; the transaction is not at fault.");
    }

    private static Problem wrote(Subject subject, RuleDescriptor rule) {
        return Problems.of(ProblemCode.RULE_FAILED, subject.path(), "The rule " + name(rule) + " tried to write while "
                + "checking " + subject.path() + ", and caught the exception that refused the write.",
                "a rule that only reads", "a write while rules run",
                "Correct the rule: it must not commit, run a transaction dry or register a schema.");
    }

    private static Problem unknownAttribute(Subject subject, RuleDescriptor rule, Reported reported) {
        String names = subject.type().attributes().stream().map(Attribute::name).collect(Collectors.joining(", "));
        String kind = subject.type().kind();
        return Problems.of(ProblemCode.RULE_FAILED, subject.path(), "The rule " + name(rule) + " reported a problem "
                + "about the attribute '" + reported.attribute() + "' while checking " + subject.path() + ", but the "
                + kind + " type " + subject.type().name() + " has no such attribute.",
                names.isEmpty() ? "an attribute of " + subject.type().name() : "one of " + names, reported.attribute(),
                "Report the problem about an attribute of the " + kind + "'s type, or about the whole " + kind + ".");
    }

    private static Problem undeclared(Subject subject, Rules.Registered registered, Reported reported) {
        RuleDescriptor rule = registered.descriptor();
        String declared = rule.codes().isEmpty() ? "no code"
                : "one of the codes " + rule.codes().stream().map(code -> code.code())
                        .collect(Collectors.joining(", "));
        return Problems.of(ProblemCode.RULE_FAILED, subject.path(), "The rule " + name(rule) + " reported the code '"
                + reported.code() + "' while checking " + subject.path() + ", which it does not declare.", declared,
                reported.code(), "Declare the code in the rule's description, or report a code that it declares.");
    }

    private static Problem notForKind(Subject subject, Rules.Registered registered) {
        RuleDescriptor rule = registered.descriptor();
        boolean relationship = subject.type().relationship();
        String article = relationship ? "a " : "an ";
        return Problems.of(ProblemCode.RULE_FAILED, subject.path(), "The rule " + name(rule) + " cannot check "
                + subject.path() + ": " + subject.type().name() + " is " + article + subject.type().kind()
                + " type, but the rule is not " + article + subject.ruleInterface() + ".",
                article + subject.ruleInterface(), registered.rule().getClass().getName(),
                "Make the rule " + article + subject.ruleInterface() + ", or name it only on "
                        + (relationship ? "entity" : "relationship") + " types.");
    }

    private static Problem unavailable(Subject subject, EffectiveRule effective) {
        String name = effective.rule().name() + " in version " + effective.rule().version();
        return Problems.of(ProblemCode.RULE_UNAVAILABLE, subject.path(), "The store has no code for the rule " + name
                + ", which " + effective.declaredBy() + " names, so " + subject.path() + " cannot be written.",
                "the code of the rule " + effective.rule().name() + " among the rules the store was created with",
                "the rule declared without code",
                "Create the store with the rule's code, or register a schema in which " + effective.declaredBy()
                        + " no longer names the rule.");
    }

    private static String name(RuleDescriptor rule) {
        return rule.name() + " in version " + rule.version();
    }

    // ------------------------------------------------------------------ the context

    /** The context of one call of a rule: it collects the reports, and is closed when the rule returns. */
    private static final class Context implements RuleContext {

        private final ProvisionalView.Source source;
        private final Instant start;
        private final List<Reported> reported = new ArrayList<>();
        private boolean closed;

        Context(ProvisionalView.Source source, Instant start) {
            this.source = source;
            this.start = start;
        }

        @Override
        public View view() {
            return source.at(start);
        }

        @Override
        public View viewAt(Instant effectiveAt) {
            return source.at(effectiveAt);
        }

        @Override
        public void problem(String code, String attribute, String message) {
            Objects.requireNonNull(attribute, "attribute");
            report(code, attribute, message);
        }

        @Override
        public void problem(String code, String message) {
            report(code, null, message);
        }

        private void report(String code, String attribute, String message) {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (closed) {
                throw new IllegalStateException("The rule has returned, so it can no longer report problems.");
            }
            reported.add(new Reported(code, attribute, message));
        }

        void close() {
            closed = true;
        }
    }
}
