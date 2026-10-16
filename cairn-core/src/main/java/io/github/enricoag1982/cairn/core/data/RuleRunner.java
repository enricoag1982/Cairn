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
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 *
 * <p>Every run of a rule records what the rule read, through the views of its context, and stages it for the
 * {@link ReadIndex} (design D1 and D3 of {@code add-rule-rechecks}). Those are the views of the state the transaction
 * would produce, and the views of the store as it was committed before the transaction, which every run reads through
 * {@code before()} and {@code beforeAt} and which are recorded in the same way (design D2 of
 * {@code add-previous-state-to-rules}). The runner also runs rules again for versions that a
 * transaction does not write, because it changes what they read: a {@linkplain #recheck re-check} reports its problems
 * about the version it checks, with a hint that says so (D4). And it can run rules only to learn what they read, without
 * judging the verdicts, as a durable store does when it opens (D5).
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

    /**
     * A rule to run again for some versions of an entity or relationship that a transaction does not write, or writes
     * without changing the versions.
     *
     * @param subject  the entity or relationship, with the path its problems have
     * @param rule     the rule, as the subject's type names it
     * @param versions the versions to check, in the order of the timeline
     */
    record Recheck(Subject subject, EffectiveRule rule, List<Segment> versions) {
    }

    /** A rule that ran for a version: the entity or relationship, the rule and the start of the version's period. */
    private record Ran(Id subject, RuleReference rule, Instant from) {
    }

    /** A problem that a rule reported through its context. */
    private record Reported(String code, String attribute, String message) {
    }

    private final Rules rules;
    private final ProvisionalView.Source source;
    /** The store as committed before the transaction, which the rules read through {@code before()}. */
    private final ProvisionalView.Source committed;
    private final CommitSequence sequence;
    private final ReadIndex.Stage stage;
    private final Set<Ran> ran = new HashSet<>();
    private final Set<RuleReference> reporting = new LinkedHashSet<>();
    /** Whether the runner only learns what rules read, so that a stack overflow in a rule is not fatal. */
    private boolean learning;

    /**
     * Creates a runner for one transaction.
     *
     * @param rules    the rules the store has code for
     * @param source   the state the rules read; the store as committed before the transaction is the same schema and
     *                 ledger with nothing laid over it, at the latest basis that the sequence has published
     * @param sequence the sequence of the store, which refuses writes while the rules run
     * @param stage    where the reads of every run are staged
     */
    RuleRunner(Rules rules, ProvisionalView.Source source, CommitSequence sequence, ReadIndex.Stage stage) {
        this.rules = rules;
        this.source = source;
        this.committed = source.committed(sequence.latestBasis());
        this.sequence = sequence;
        this.stage = stage;
    }

    /** Tells whether a rule ran for the version of an entity or relationship that starts at an instant. */
    boolean ran(Id subject, RuleReference rule, Instant from) {
        return ran.contains(new Ran(subject, rule, from));
    }

    /** The rules that reported a problem, of any kind, about a subject the runner ran rules for. */
    Set<RuleReference> reporting() {
        return Set.copyOf(reporting);
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

    /**
     * Runs the rules of the subjects only to learn what they read, as a store does after recovery (design D5 of
     * {@code add-rule-rechecks}): what the rules report, throw or try to write is dropped, and what they read is staged.
     * The same as {@link #run}, called with the writer lock held, for subjects whose {@code changed} are all their
     * versions. A rule that overflows its stack is dropped like one that throws an exception, and what it read before is
     * kept, so that it does not stop the store from opening; unlike a commit, which leaves the store as it was.
     *
     * @param subjects the entities and relationships whose rules run
     * @throws VirtualMachineError if a rule causes one other than a {@link StackOverflowError}, such as an
     *                             {@code OutOfMemoryError}; it is not turned into a problem
     */
    void learn(List<Subject> subjects) {
        learning = true;
        try {
            run(subjects);
        } finally {
            learning = false;
        }
    }

    /**
     * Runs rules again for versions, because a transaction changes what the rules read when they last checked them
     * (design D4 of {@code add-rule-rechecks}). Called with the writer lock held, as {@link #run} is. A rule that the
     * store has no code for, or that does not fit the kind of the subject, is left alone: it has no reads to be reached.
     *
     * @param rechecks the rules to run again, in the order in which their problems come
     * @return the problems, which have the path of the subject and a hint that says that the transaction changed what the
     *         rule read; none if every rule is content
     * @throws VirtualMachineError if a rule causes one, such as an {@code OutOfMemoryError}; it is not turned into a
     *                             problem
     */
    List<Problem> recheck(List<Recheck> rechecks) {
        return sequence.whileRunningRules(() -> {
            List<Problem> problems = new ArrayList<>();
            for (Recheck recheck : rechecks) {
                Optional<Rules.Registered> registered = rules.find(recheck.rule().rule());
                if (registered.isEmpty() || !recheck.subject().fits(registered.get().rule())) {
                    continue;
                }
                String hint = "The transaction changes what the rule read when it last checked "
                        + recheck.subject().path() + ".";
                Set<Problem> found = new LinkedHashSet<>();
                for (Segment version : recheck.versions()) {
                    if (!check(recheck.subject(), registered.get(), recheck.rule().rule(), version, hint, found)) {
                        break;
                    }
                }
                if (!found.isEmpty()) {
                    reporting.add(recheck.rule().rule());
                }
                problems.addAll(found);
            }
            return problems;
        });
    }

    private List<Problem> runAll(List<Subject> subjects) {
        List<Problem> problems = new ArrayList<>();
        for (Subject subject : subjects) {
            Set<Problem> found = new LinkedHashSet<>();
            for (EffectiveRule effective : subject.type().effectiveRules()) {
                Set<Problem> mine = new LinkedHashSet<>();
                runRule(subject, effective, mine);
                if (!mine.isEmpty()) {
                    reporting.add(effective.rule());
                }
                found.addAll(mine);
            }
            problems.addAll(found);
        }
        return problems;
    }

    /** Runs one rule of a subject's type for the subject's changed segments, and collects what it reports. */
    private void runRule(Subject subject, EffectiveRule effective, Set<Problem> found) {
        Optional<Rules.Registered> registered = rules.find(effective.rule());
        if (registered.isEmpty()) {
            found.add(unavailable(subject, effective));
            return;
        }
        if (subject.changed().isEmpty()) {
            return; // The rule has code, and a write that changes no values runs no rules.
        }
        if (!subject.fits(registered.get().rule())) {
            found.add(notForKind(subject, registered.get()));
            return;
        }
        for (Segment segment : subject.changed()) {
            if (!check(subject, registered.get(), effective.rule(), segment, null, found)) {
                break;
            }
        }
    }

    /**
     * Runs a rule for one segment; tells whether it ran to its end. Anything the rule throws is a failure of the rule,
     * except a {@link VirtualMachineError}: when the virtual machine is out of resources, nothing can be said about the
     * rule, so the error goes on. A rule that tried to write has failed too, even if it caught the refusal and returned:
     * the sequence counts the refused writes, and if the count grew while the rule ran, what the rule reported is
     * dropped, as for an exception.
     *
     * <p>What the rule read is staged either way, for the version's whole period: a transaction that is rejected stages
     * nothing that is applied, and a store that learns what its rules read wants the reads of a rule that threw.
     *
     * @param hint a hint to add to every problem, or null for none; a re-check says that the transaction changed what the
     *             rule read
     */
    private boolean check(Subject subject, Rules.Registered registered, RuleReference rule, Segment segment,
            String hint, Set<Problem> problems) {
        Consumer<RuleContext> call = call(subject, registered.rule(), segment);
        Reads reads = new Reads();
        Context context = new Context(source, committed, segment.from(), reads);
        ran.add(new Ran(subject.id(), rule, segment.from()));
        long refusedBefore = sequence.refusedWrites();
        try {
            call.accept(context);
        } catch (Throwable e) {
            // The virtual machine is out of resources or failing: nothing can be said about the rule, so the error goes
            // on. Only a stack that a rule overflowed says something about the rule, and a store that learns what its
            // rules read does not let one stop it from opening.
            if (e instanceof VirtualMachineError error && !(learning && e instanceof StackOverflowError)) {
                throw error;
            }
            problems.add(withHint(threw(subject, registered.descriptor(), e), hint));
            return false;
        } finally {
            context.close();
            stage.ran(subject.id(), rule, segment.from(), segment.end(), reads);
        }
        if (sequence.refusedWrites() != refusedBefore) {
            problems.add(withHint(wrote(subject, registered.descriptor()), hint));
            return false;
        }
        for (Reported reported : context.reported) {
            problems.add(withHint(problem(subject, registered, reported), hint));
        }
        return true;
    }

    /** The problem with a hint added to its own, if it has one. */
    private static Problem withHint(Problem problem, String hint) {
        if (hint == null) {
            return problem;
        }
        String joined = problem.hint().map(own -> own + " " + hint).orElse(hint);
        return new Problem(problem.code(), problem.path(), problem.message(), problem.expected(), problem.found(),
                Optional.of(joined), problem.location(), problem.ruleCode());
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

    /**
     * The context of one call of a rule: it collects the reports, and is closed when the rule returns. Its views record
     * what the rule reads into the reads of the call (design D1 of {@code add-rule-rechecks}), those of the state the
     * transaction would produce and those of the committed state before it (design D2 of
     * {@code add-previous-state-to-rules}) alike.
     */
    private static final class Context implements RuleContext {

        private final ProvisionalView.Source source;
        private final ProvisionalView.Source committed;
        private final Instant start;
        private final Reads reads;
        private final List<Reported> reported = new ArrayList<>();
        private boolean closed;

        Context(ProvisionalView.Source source, ProvisionalView.Source committed, Instant start, Reads reads) {
            this.source = source;
            this.committed = committed;
            this.start = start;
            this.reads = reads;
        }

        @Override
        public View view() {
            return source.at(start, reads);
        }

        @Override
        public View viewAt(Instant effectiveAt) {
            return source.at(effectiveAt, reads);
        }

        @Override
        public View before() {
            return committed.at(start, reads);
        }

        @Override
        public View beforeAt(Instant effectiveAt) {
            return committed.at(effectiveAt, reads);
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
            reads.close();
        }
    }
}
