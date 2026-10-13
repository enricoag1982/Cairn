package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI_WRONG_CHECK_DIGITS;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.NameNotBlankRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * How rules run at commit (design D3 and D4 of {@code add-entity-rules}): which versions they check, what they see
 * through their context, and how what they report becomes problems.
 */
class EntityRulesTest {

    private static final TemporaryId DELTA = new TemporaryId("delta");
    private static final TemporaryId CAROL = new TemporaryId("carol");

    /** A store of the reference domain whose schema also names the given rules, each on the type it applies to. */
    private static ReferenceStore storeWith(Rule... extra) {
        SchemaDefinition model = ReferenceModel.definition();
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        for (Rule rule : extra) {
            model = TestEntityRule.namedIn(model, rule.descriptor().appliesTo(), rule.descriptor());
            rules.add(rule);
        }
        return ReferenceStore.withRules(model, rules, ReferenceModel.declaredRules());
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** A rule on Organization that records every version it checks. */
    private static TestEntityRule recorder(List<EntityState> checked) {
        return TestEntityRule.of("recorder", "Organization", (version, context) -> checked.add(version));
    }

    private static Value name(EntityState state) {
        return state.values().get("name");
    }

    private static String text(EntityState state) {
        return ((Value.StringValue) name(state)).value();
    }

    // ------------------------------------------------------------------ what the rule sees

    /** What a rule saw of the provisional view while it checked one version. */
    private record Snapshot(
            Basis basis,
            Instant effectiveAt,
            Schema schema,
            List<EntityState> parties,
            Optional<EntityState> carol,
            List<EntityState> betaTimeline,
            EntityState version) {
    }

    private Transaction deltaCarolAndBetaRenamed(EntityId beta) {
        return Transaction.by("importer")
                .create(DELTA, "Organization", from(1, 1), values("name", "Delta"))
                .create(CAROL, "Person", from(1, 1), values("name", "Carol"))
                .patch(beta, from(1, 1), values("name", "Beta Two")).build();
    }

    private TestEntityRule snapshotOfDelta(AtomicReference<Snapshot> snapshot) {
        return TestEntityRule.of("snapshot", "Organization", (version, context) -> {
            if (name(version).equals(Value.of("Delta"))) {
                View view = context.view();
                snapshot.set(new Snapshot(view.basis(), view.effectiveAt(), view.schema(),
                        view.entities("Party"), view.entity(id(5)), view.timeline(id(2)), version));
            }
        });
    }

    @Test
    void theProvisionalViewShowsTheStateTheTransactionWouldProduce() {
        AtomicReference<Snapshot> snapshot = new AtomicReference<>();
        ReferenceStore reference = storeWith(snapshotOfDelta(snapshot));
        reference.createParties();
        Basis prospective = new Basis(reference.store.latestBasis().value() + 1);

        Receipt receipt = reference.commit(deltaCarolAndBetaRenamed(id(2)));

        Snapshot seen = snapshot.get();
        assertThat(seen.basis()).isEqualTo(prospective).isEqualTo(receipt.basis());
        assertThat(seen.effectiveAt()).isEqualTo(on(1, 1));
        assertThat(seen.schema()).isEqualTo(reference.store.schema().current());
        assertThat(seen.parties()).extracting(EntityState::id, state -> name(state), EntityState::recordedBasis)
                .as("committed and new entities in ID order, the changed one as it would be")
                .containsExactly(
                        tuple(id(1), Value.of("ACME"), new Basis(2)),
                        tuple(id(2), Value.of("Beta Two"), prospective),
                        tuple(id(3), Value.of("Alice"), new Basis(2)),
                        tuple(id(4), Value.of("Delta"), prospective),
                        tuple(id(5), Value.of("Carol"), prospective));
        assertThat(seen.carol()).hasValueSatisfying(carol -> assertThat(carol.entityType()).isEqualTo("Person"));
        assertThat(seen.betaTimeline()).extracting(EntityState::values)
                .containsExactly(java.util.Map.of("name", Value.of("Beta Two")));
        assertThat(seen.version().id()).isEqualTo(id(4));
        assertThat(seen.version().entityType()).isEqualTo("Organization");
        assertThat(seen.version().recordedBasis()).isEqualTo(prospective);
        assertThat(seen.version().period().from()).isEqualTo(on(1, 1));
    }

    @Test
    void aDryRunGivesTheRulesTheBasisTheCommitWouldGetAndRecordsNothing() {
        AtomicReference<Snapshot> snapshot = new AtomicReference<>();
        ReferenceStore reference = storeWith(snapshotOfDelta(snapshot));
        reference.createParties();
        Basis latest = reference.store.latestBasis();

        DryRunResult result = reference.store.dryRun(deltaCarolAndBetaRenamed(id(2)));

        assertThat(result).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(snapshot.get().basis()).isEqualTo(new Basis(latest.value() + 1));
        assertThat(reference.store.latestBasis()).isEqualTo(latest);
        assertThat(reference.view(on(2, 1)).entity(id(4))).isEmpty();
    }

    @Test
    void viewAtGivesTheSameStateAtAnotherEffectiveTime() {
        List<Value> beta = new ArrayList<>();
        List<Instant> asked = new ArrayList<>();
        TestEntityRule probe = TestEntityRule.of("probe", "Organization", (version, context) -> {
            if (version.id().equals(id(1))) {
                for (Instant at : List.of(on(1, 1), on(2, 28), on(3, 1), on(6, 1))) {
                    View view = context.viewAt(at);
                    asked.add(view.effectiveAt());
                    beta.add(name(view.entity(id(2)).orElseThrow()));
                }
                beta.add(name(context.view().entity(id(2)).orElseThrow()));
                assertThat(context.viewAt(on(2025, 12, 31)).entity(id(2))).isEmpty();
            }
        });
        ReferenceStore reference = storeWith(probe);
        reference.createParties();
        beta.clear();
        asked.clear();

        reference.commit(Transaction.by("importer")
                .patch(id(1), from(1, 1), values("name", "ACME Two"))
                .patch(id(2), from(3, 1), values("name", "Beta Two")).build());

        assertThat(asked).containsExactly(on(1, 1), on(2, 28), on(3, 1), on(6, 1));
        assertThat(beta).containsExactly(Value.of("Beta"), Value.of("Beta"), Value.of("Beta Two"),
                Value.of("Beta Two"), Value.of("Beta"));
    }

    @Test
    void aViewShowsAnEntityThatTheTransactionEndsAsEnded() {
        List<Boolean> betaExists = new ArrayList<>();
        TestEntityRule probe = TestEntityRule.of("probe", "Organization", (version, context) -> {
            if (version.id().equals(id(1))) {
                betaExists.add(context.viewAt(on(5, 1)).entity(id(2)).isPresent());
                betaExists.add(context.viewAt(on(7, 1)).entity(id(2)).isPresent());
            }
        });
        ReferenceStore reference = storeWith(probe);
        reference.createParties();
        betaExists.clear();

        reference.commit(Transaction.by("importer")
                .patch(id(1), from(1, 1), values("name", "ACME Two"))
                .endDate(id(2), on(6, 1)).build());

        assertThat(betaExists).containsExactly(true, false);
    }

    @Test
    void aViewOfTheTransactionAnswersLikeOtherViews() {
        List<String> answers = new ArrayList<>();
        TestEntityRule probe = TestEntityRule.of("probe", "Organization", (version, context) -> {
            if (version.id().equals(id(1))) {
                View view = context.view();
                answers.add(view.entities("Organization").size() + " organizations");
                answers.add(view.entities("Person").size() + " persons");
                answers.add(view.entities("organization").size() + " organizations by lower case");
                answers.add(view.entity(id(999)).isPresent() + " for an unknown ID");
                answers.add(view.timeline(id(999)).size() + " versions of an unknown ID");
                try {
                    view.entities("Spaceship");
                } catch (IllegalArgumentException e) {
                    answers.add("no such type");
                }
            }
        });
        ReferenceStore reference = storeWith(probe);

        reference.createParties();

        assertThat(answers).containsExactly("2 organizations", "1 persons", "2 organizations by lower case",
                "false for an unknown ID", "0 versions of an unknown ID", "no such type");
    }

    // ------------------------------------------------------------------ which versions are checked

    @Test
    void onlyTheSegmentsThatDifferFromThePreviousTimelineAreChecked() {
        List<EntityState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        reference.createParties();
        assertThat(checked).as("created").extracting(EntityState::id).containsExactlyInAnyOrder(id(1), id(2));
        checked.clear();

        reference.commit(Transaction.by("importer").patch(id(1), from(3, 1), values("name", "ACME Two")).build());
        assertThat(checked).as("the changed part of the timeline").singleElement()
                .satisfies(version -> assertThat(version.period().from()).isEqualTo(on(3, 1)))
                .satisfies(version -> assertThat(version.period().until()).isEmpty());
        checked.clear();

        reference.commit(Transaction.by("importer").patch(id(1), from(3, 1), values("name", "ACME Two")).build());
        reference.commit(Transaction.by("importer")
                .replace(id(1), from(3, 1), values("name", "ACME Two")).build());
        reference.commit(Transaction.by("importer").patch(id(2), from(1, 1), values("name", "Beta")).build());
        assertThat(checked).as("writes that change nothing").isEmpty();

        reference.commit(Transaction.by("importer")
                .patch(id(1), EffectivePeriod.between(on(5, 1), on(6, 1)), values("name", "ACME Three")).build());
        assertThat(checked).as("only the closed period of the change").singleElement()
                .satisfies(version -> assertThat(version.period()).isEqualTo(EffectivePeriod.between(on(5, 1), on(6, 1))))
                .satisfies(version -> assertThat(name(version)).isEqualTo(Value.of("ACME Three")));
        checked.clear();

        reference.commit(Transaction.by("importer").patch(id(2), from(3, 1), values("name", "Beta Two")).build());
        assertThat(checked).as("an entity that the transaction does not change").extracting(EntityState::id)
                .containsExactly(id(2));
        checked.clear();

        reference.commit(Transaction.by("importer").endDate(id(1), on(8, 1)).build());
        assertThat(checked).as("cutting off an entity changes no values").isEmpty();
    }

    @Test
    void everyChangedSegmentOfANewEntityIsChecked() {
        List<EntityState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));

        reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "ACME Two")).build());

        assertThat(checked).extracting(version -> version.period().from(), EntityRulesTest::name)
                .containsExactly(tuple(on(1, 1), Value.of("ACME")), tuple(on(3, 1), Value.of("ACME Two")));
    }

    @Test
    void aRuleRunsForEveryEntityTypeThatNamesItOrASupertypeDoes() {
        List<String> checked = new ArrayList<>();
        TestEntityRule forParty = TestEntityRule.of("party-probe", "Party",
                (version, context) -> checked.add(version.entityType() + ":" + text(version)));
        ReferenceStore reference = storeWith(forParty);

        reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        assertThat(checked).containsExactly("Organization:ACME", "Person:Alice");
    }

    @Test
    void anEntityTypesOwnRulesRunBeforeThoseOfItsSupertypes() {
        ReferenceStore reference = ReferenceStore.withRules(
                TestEntityRule.namedIn(ReferenceModel.definition(), "Party", NameNotBlankRule.DESCRIPTOR),
                List.of(new LeiChecksumRule(), new NameNotBlankRule()), ReferenceModel.declaredRules());

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", " ", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path, problem -> problem.ruleCode().orElseThrow())
                .containsExactly(
                        tuple(ProblemCode.RULE_VIOLATION, "$gamma.lei", "lei-checksum/invalid-check-digits"),
                        tuple(ProblemCode.RULE_VIOLATION, "$gamma.name", "name-not-blank/blank-name"));
    }

    // ------------------------------------------------------------------ declarative checks come first

    @Test
    void rulesDoNotRunForAnEntityWithADeclarativeProblem() {
        List<EntityState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", "bad"))
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.VALUE_PATTERN_MISMATCH, "$acme.lei"),
                tuple(ProblemCode.RULE_VIOLATION, "$gamma.lei"));
        assertThat(checked).extracting(EntityRulesTest::name).containsExactly(Value.of("Gamma"));
    }

    @Test
    void anEntityWhoseWriteConflictsIsNotChecked() {
        List<EntityState> checked = new ArrayList<>();
        ReferenceStore reference = storeWith(recorder(checked));
        reference.createParties();
        checked.clear();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(id(500), from(3, 1), values("name", "x"))
                .patch(id(1), from(3, 1), values("nmae", "x")).build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_ENTITY,
                ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(checked).isEmpty();
    }

    // ------------------------------------------------------------------ paths

    @Test
    void aRuleProblemIsLocatedLikeOtherDataProblems() {
        TestEntityRule whole = TestEntityRule.of("whole", "Organization", (version, context) -> {
            if (name(version).equals(Value.of("Whole"))) {
                context.problem("entity", "The whole entity is wrong.");
            }
        }, "entity");
        ReferenceStore reference = storeWith(whole);
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);

        List<Problem> withTemporaryId = reference.reject(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());
        List<Problem> withoutTemporaryId = reference.reject(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "Delta"))
                .create("Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());
        List<Problem> suppliedId = reference.reject(Transaction.by("importer")
                .create(id(900), "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());
        List<Problem> existing = reference.reject(Transaction.by("importer")
                .patch(acme, from(3, 1), values("lei", LEI_WRONG_CHECK_DIGITS)).build());
        List<Problem> wholeEntity = reference.reject(Transaction.by("importer")
                .create(DELTA, "Organization", from(1, 1), values("name", "Whole")).build());

        assertThat(only(withTemporaryId).path()).isEqualTo("$gamma.lei");
        assertThat(only(withoutTemporaryId).path()).isEqualTo("@write.1.lei");
        assertThat(only(suppliedId).path()).isEqualTo(id(900).value() + ".lei");
        assertThat(only(existing).path()).isEqualTo(acme.value() + ".lei");
        Problem problem = only(wholeEntity);
        assertThat(problem.path()).isEqualTo("$delta");
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("whole/entity");
        assertThat(problem.message()).isEqualTo("The whole entity is wrong.");
    }

    @Test
    void aProblemThatSeveralSegmentsShareIsReportedOnce() {
        ReferenceStore reference = new ReferenceStore();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .patch(GAMMA, from(3, 1), values("name", "Gamma Two")).build());

        assertThat(only(problems).path()).isEqualTo("$gamma.lei");
    }

    // ------------------------------------------------------------------ failing rules

    @Test
    void aRuleThatThrowsGivesAProblemWithTheExceptionsMessageAndTheOtherEntitiesAreStillChecked() {
        TestEntityRule picky = TestEntityRule.of("picky", "Organization", (version, context) -> {
            context.problem("early", "Reported before the exception.");
            if (name(version).equals(Value.of("Bad"))) {
                throw new IllegalArgumentException("no good");
            }
        }, "early");
        ReferenceStore reference = storeWith(picky);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "Bad"))
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.RULE_FAILED, "$acme"),
                tuple(ProblemCode.RULE_VIOLATION, "$gamma.lei"),
                tuple(ProblemCode.RULE_VIOLATION, "$gamma"));
        Problem failed = problems.getFirst();
        assertThat(failed.found()).contains("no good");
        assertThat(failed.message()).contains("picky").contains("IllegalArgumentException");
        assertThat(failed.ruleCode()).as("a failure has no rule code").isEmpty();
    }

    @Test
    void anExceptionWithoutAMessageIsNamedByItsClass() {
        TestEntityRule broken = TestEntityRule.of("broken", "Organization", (version, context) -> {
            throw new UnsupportedOperationException();
        });
        ReferenceStore reference = storeWith(broken);

        Problem problem = only(reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.found()).contains("java.lang.UnsupportedOperationException");
    }

    @Test
    void aRuleThatFailsForOneSegmentIsNotRunForTheOthersOfTheEntity() {
        List<EntityState> calls = new ArrayList<>();
        TestEntityRule broken = TestEntityRule.of("broken", "Organization", (version, context) -> {
            calls.add(version);
            throw new IllegalStateException("fails every time");
        });
        ReferenceStore reference = storeWith(broken);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "ACME Two")).build());

        assertThat(only(problems).code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(calls).hasSize(1);
    }

    @Test
    void aCodeThatTheRuleDoesNotDeclareIsAFailureOfTheRule() {
        TestEntityRule sloppy = TestEntityRule.of("sloppy", "Organization", (version, context) -> {
            context.problem("declared", "name", "A real problem.");
            context.problem("surprise", "name", "An undeclared one.");
        }, "declared");
        ReferenceStore reference = storeWith(sloppy);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertThat(problems).extracting(Problem::code, Problem::path, problem -> problem.ruleCode().orElse(""))
                .containsExactly(
                        tuple(ProblemCode.RULE_VIOLATION, "$acme.name", "sloppy/declared"),
                        tuple(ProblemCode.RULE_FAILED, "$acme", ""));
        Problem failed = problems.get(1);
        assertThat(failed.found()).contains("surprise");
        assertThat(failed.message()).contains("sloppy").contains("surprise");
        assertThat(failed.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("declared"));
    }

    @Test
    void aRuleThatIsNotAnEntityRuleCannotCheckAnEntity() {
        RuleDescriptor descriptor = new RuleDescriptor("plain-rule", 1, "Describes itself only.", "Organization",
                List.of());
        Rule plain = () -> descriptor;
        SchemaDefinition model = TestEntityRule.namedIn(ReferenceModel.definition(), "Organization", descriptor);
        ReferenceStore reference = ReferenceStore.withRules(model, List.of(new LeiChecksumRule(), plain),
                ReferenceModel.declaredRules());

        Problem problem = only(reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo("$acme");
        assertThat(problem.message()).contains("plain-rule").contains("EntityRule");
        assertThat(reference.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build())).isNotNull();
    }

    @Test
    void aRuleCannotReportAfterItHasReturned() {
        AtomicReference<RuleContext> kept = new AtomicReference<>();
        TestEntityRule keeping = TestEntityRule.of("keeping", "Organization",
                (version, context) -> kept.set(context), "late");
        ReferenceStore reference = storeWith(keeping);
        reference.commit(Transaction.by("importer").create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .build());

        assertThatThrownBy(() -> kept.get().problem("late", "Too late."))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> kept.get().problem("late", "name", "Too late."))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRuleThatReportsWithoutACodeOrMessageFails() {
        TestEntityRule careless = TestEntityRule.of("careless", "Organization",
                (version, context) -> context.problem(null, "No code."));
        ReferenceStore reference = storeWith(careless);

        Problem problem = only(reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.message()).contains("careless");
    }

    // ------------------------------------------------------------------ rules without code

    @Test
    void aDeclaredRuleIsReportedOncePerEntityAndOnlyForWritesThatNeedIt() {
        ReferenceStore reference = ReferenceStore.withRules(ReferenceModel.definition(), List.of(),
                ReferenceModel.rules());

        List<Problem> organization = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "ACME Two")).build());
        DryRunResult dry = reference.store.dryRun(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());
        Receipt person = reference.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        Problem problem = only(organization);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo("$acme");
        assertThat(problem.message()).contains("lei-checksum");
        assertThat(problem.ruleCode()).isEmpty();
        assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(person.created()).hasSize(1);
    }

    @Test
    void aRuleThatIsNeverNeededDoesNotMatterWhetherItHasCode() {
        ReferenceStore reference = ReferenceStore.withRules(ReferenceModel.definition(), List.of(),
                ReferenceModel.rules());
        CommitResult result = reference.store.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    void validAndInvalidLeisAreToldApartWithTheOtherLeisOfTheReferenceDomain() {
        ReferenceStore reference = new ReferenceStore();

        reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta", "lei", "HWUPKR0MPOU8FGXBT394"))
                .build());
        Problem problem = only(reference.reject(Transaction.by("importer")
                .patch(id(1), from(3, 1), values("lei", LEI_WRONG_CHECK_DIGITS)).build()));

        assertThat(problem.ruleCode()).contains("lei-checksum/invalid-check-digits");
    }
}
