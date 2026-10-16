package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.schema.AppliedCheck;
import io.github.enricoag1982.cairn.core.schema.CheckedSchema;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How the engine compiles and runs checks, beyond the scenarios of the {@code checks} spec (design D2 and D4 of
 * {@code add-cel-expressions}). Checks in CEL need the module {@code cairn-cel}, which the tests have on their class path;
 * the failures of a language that the engine must survive are shown with {@link StubLanguage}.
 */
class ChecksTest {

    private static final String NON_NEGATIVE = "self.balance >= 0.0";

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** The problems about checks: accounts created without a holder have the problems of their relationships too. */
    private static List<Problem> aboutChecks(List<Problem> problems) {
        return problems.stream().filter(problem -> problem.code().name().startsWith("CHECK")).toList();
    }

    private static SchemaDefinition accountChecked(String expression) {
        return withCheck(ReferenceModel.definition(), "Account", "a-check", expression, null);
    }

    private static ReferenceStore storeWith(SchemaDefinition definition) {
        return ReferenceStore.withRules(definition, ReferenceModel.executableRules(), ReferenceModel.declaredRules());
    }

    // ------------------------------------------------------------------ what the schema keeps

    @Test
    void theSchemaKeepsTheCompiledChecksOfTheConcreteTypesThatInheritThem() {
        StubLanguage language = new StubLanguage();
        SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(),
                Optional.of(language));
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Party", "name-set", "true", null);

        Schema schema = ((RegistrationResult.Applied) registry.register(ReferenceModel.origin("1.0.0"), definition))
                .schema();

        CheckedSchema checked = (CheckedSchema) schema;
        EntityType person = schema.entityType("Person").orElseThrow();
        EntityType organization = schema.entityType("Organization").orElseThrow();
        assertThat(checked.checks(person.id())).singleElement().satisfies(applied -> {
            assertThat(applied.check().declaredBy()).isEqualTo("Party");
            assertThat(applied.compiled()).isPresent();
            assertThat(applied.unavailable()).isFalse();
        });
        assertThat(checked.checks(organization.id())).hasSize(1);
        assertThat(checked.checks(schema.entityType("Party").orElseThrow().id())).as("an abstract type has no entities")
                .isEmpty();
        assertThat(checked.checks(schema.entityType("Account").orElseThrow().id())).isEmpty();
        assertThat(language.compiled()).as("against Party for the validation, and each concrete subtype")
                .extracting(compiled -> compiled.attributes().keySet())
                .containsExactlyInAnyOrder(Set.of("name"), Set.of("name", "birthDate"),
                        Set.of("name", "lei"));
    }

    @Test
    void aTypeWithoutChecksHasNoneAndAnUnknownTypeNeither() {
        Schema schema = ((RegistrationResult.Applied) SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(),
                Optional.empty()).register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition())).schema();

        assertThat(((CheckedSchema) schema).checks(schema.entityType("Account").orElseThrow().id())).isEmpty();
        assertThat(((CheckedSchema) schema).checks(new TypeId(9_999))).isEmpty();
    }

    @Test
    void anAppliedCheckIsUnavailableOnlyIfItIsNeitherCompiledNorFailed() {
        EffectiveCheck check = new EffectiveCheck(new Check("a", "true"), "Account");

        assertThat(new AppliedCheck(check, Optional.empty(), Optional.empty()).unavailable()).isTrue();
        assertThat(new AppliedCheck(check, Optional.empty(), Optional.of("It failed.")).unavailable()).isFalse();
    }

    // ------------------------------------------------------------------ the language of the settings and registries

    @Test
    void theBuilderTakesTheLanguageItIsGivenInsteadOfLookingForOne() {
        StubLanguage language = new StubLanguage();

        assertThat(StoreSettings.builder().expressionLanguage(language).build().expressionLanguage())
                .containsSame(language);
        assertThat(StoreSettings.builder().noExpressionLanguage().build().expressionLanguage()).isEmpty();
        assertThat(StoreSettings.builder().noExpressionLanguage().expressionLanguage(language).build()
                .expressionLanguage()).containsSame(language);
        assertThat(StoreSettings.builder().expressionLanguage(language).noExpressionLanguage().build()
                .expressionLanguage()).isEmpty();
    }

    @Test
    void theBuilderAndTheDefaultsLookForALanguageAndFindTheOneOnTheClassPath() {
        assertThat(StoreSettings.builder().build().expressionLanguage()).hasValueSatisfying(language ->
                assertThat(language.name()).isEqualTo("cel"));
        assertThat(StoreSettings.defaults().expressionLanguage()).isPresent();
        assertThat(ExpressionLanguages.discovered()).isPresent();
    }

    @Test
    void settingsMadeWithTheirConstructorsHaveNoLanguage() {
        assertThat(new StoreSettings(new TestClock(), IdGenerators.deterministic(), List.of()).expressionLanguage())
                .isEmpty();
        assertThat(new StoreSettings(new TestClock(), IdGenerators.deterministic(), List.of(), List.of())
                .expressionLanguage()).isEmpty();
    }

    @Test
    void aStoreUsesTheLanguageOfItsSettings() {
        StubLanguage language = new StubLanguage();
        var store = Stores.inMemory(StoreSettings.builder().rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).expressionLanguage(language).build());

        var problems = store.schema().validate(ReferenceModel.origin("1.0.0"), accountChecked("rejected"));

        assertThat(only(problems).code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(language.compiled()).extracting(StubLanguage.Compiled::expression).contains("rejected");
    }

    @Test
    void aRegistryWithoutALanguageRejectsChecksAlthoughOneCanBeFound() {
        SchemaRegistry without = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(), Optional.empty());
        SchemaRegistry found = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules());
        SchemaRegistry defaults = SchemaRegistries.inMemory();

        assertThat(only(without.validate(ReferenceModel.origin("1.0.0"), accountChecked(NON_NEGATIVE))).code())
                .isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
        assertThat(found.validate(ReferenceModel.origin("1.0.0"), accountChecked(NON_NEGATIVE))).isEmpty();
        assertThat(SchemaRegistries.inMemory(new TestClock()).validate(ReferenceModel.origin("1.0.0"),
                withCheck(SchemaDefinition.builder().entityType("Account", e -> e.attribute("balance",
                        ValueType.DECIMAL)).build(), "Account", "a-check", NON_NEGATIVE, null))).isEmpty();
        assertThat(defaults.current().isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ run at commit

    @Test
    void aDryRunReportsTheSameProblemsAsACommit() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                NON_NEGATIVE, "A balance cannot be negative."));
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);
        Transaction negative = Transaction.by(PRINCIPAL).patch(a1, from(4, 1), values("balance", -5)).build();

        DryRunResult dryRun = reference.store.dryRun(negative);
        List<Problem> committed = reference.reject(negative);

        assertThat(dryRun).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dryRun).problems()).isEqualTo(committed);
        assertThat(only(committed).code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
    }

    @Test
    void everyChangedSegmentIsCheckedAndNamedByItsStart() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Account", "euros-only",
                "self.currency == 'EUR'", "Only euros."));
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(2, 1), values("currency", "USD")).build());

        assertThat(problems).hasSize(2).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
            assertThat(problem.path()).isEqualTo(a1.value());
        });
        assertThat(problems.get(0).message()).contains("in the version from 2026-02-01T00:00:00Z");
        assertThat(problems.get(1).message()).contains("in the version from 2026-03-15T00:00:00Z");
    }

    @Test
    void aWriteThatChangesOneSegmentDoesNotNameItsStart() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                NON_NEGATIVE, null));
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);

        Problem problem = only(reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("balance", -5)).build()));

        assertThat(problem.message()).doesNotContain("in the version from")
                .contains("self.balance >= 0.0").contains("balance-not-negative");
    }

    @Test
    void onlyTheSegmentsThatAWriteChangesAreChecked() {
        StubLanguage language = new StubLanguage();
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(accountChecked("true"), language);
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);
        assertThat(language.evaluations()).as("the two accounts that RD-5 creates").isEqualTo(2);

        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        assertThat(language.evaluations()).as("the segment that the patch adds, not the one before").isEqualTo(3);

        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(3, 15), values("balance", 1500)).build());
        assertThat(language.evaluations()).as("a write that changes no value").isEqualTo(3);

        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(5, 1), values("number", "A-1")).build());
        assertThat(language.evaluations()).as("a write that sets what is set already").isEqualTo(3);
    }

    @Test
    void aValueThatDoesNotFitItsAttributeHasItsOwnProblemAndTheCheckIsNotAsked() {
        StubLanguage language = new StubLanguage();
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(accountChecked("false"), language);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", "lots"))
                .build());

        assertThat(problems).extracting(Problem::code).contains(ProblemCode.VALUE_TYPE_MISMATCH)
                .doesNotContain(ProblemCode.CHECK_VIOLATED, ProblemCode.CHECK_FAILED);
        assertThat(language.evaluations()).isZero();
    }

    @Test
    void theProblemsOfTheValuesComeBeforeThoseOfTheChecks() {
        ReferenceStore reference = storeWith(accountChecked(NON_NEGATIVE));

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "euro", "balance", -1))
                .build());

        assertThat(problems).extracting(Problem::code)
                .containsSubsequence(ProblemCode.VALUE_PATTERN_MISMATCH, ProblemCode.CHECK_VIOLATED);
    }

    @Test
    void aViolatedCheckKeepsTheRulesOfTheSameEntityFromRunning() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Organization", "name-set",
                "size(self.name) > 0", null));
        Transaction wrongLei = Transaction.by(PRINCIPAL)
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei",
                        ReferenceStories.LEI_WRONG_CHECK_DIGITS)).build();
        Transaction wrongLeiAndNoName = Transaction.by(PRINCIPAL)
                .create(ACME, "Organization", from(1, 1), values("name", "", "lei",
                        ReferenceStories.LEI_WRONG_CHECK_DIGITS)).build();

        assertThat(only(reference.reject(wrongLei)).code()).as("the rule runs").isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(only(reference.reject(wrongLeiAndNoName)).code()).as("the check first")
                .isEqualTo(ProblemCode.CHECK_VIOLATED);
    }

    @Test
    void aCheckOfASupertypeAppliesToEveryConcreteSubtype() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Party", "name-set",
                "size(self.name) > 0", "A party has a name."));

        Problem person = only(reference.reject(Transaction.by(PRINCIPAL)
                .create(ALICE, "Person", from(1, 1), values("name", "")).build()));
        Problem organization = only(reference.reject(Transaction.by(PRINCIPAL)
                .create(ACME, "Organization", from(1, 1), values("name", "")).build()));

        assertThat(person.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(person.path()).isEqualTo("$alice");
        assertThat(organization.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(organization.message()).contains("name-set").contains("A party has a name.");
    }

    @Test
    void checksOfEntitiesAndRelationshipsAreCheckedInOneTransaction() {
        SchemaDefinition definition = withCheck(withCheck(ReferenceModel.definition(), "Party", "name-set",
                "size(self.name) > 0", null), "Employs", "title-set", "has(self.title)", null);
        ReferenceStore reference = storeWith(definition);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(BETA, "Organization", from(1, 1), values("name", ""))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .relate(BETA_EMPLOYS_ALICE, "Employs", BETA, ALICE, from(3, 1), values()).build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.CHECK_VIOLATED,
                ProblemCode.CHECK_VIOLATED);
        assertThat(problems).extracting(Problem::path).containsExactly("$beta", "$betaEmploysAlice");
    }

    @Test
    void aSubtypeOfARelationshipTypeInheritsItsChecks() {
        SchemaDefinition model = Definitions.plus(ReferenceModel.definition(),
                SchemaDefinition.builder().relationshipType("EmploysFullTime", r -> r.from("Organization")
                        .to("Person").inverse("fullTimeEmployedBy").supertype("Employs")).build());
        ReferenceStore reference = storeWith(withCheck(model, "Employs", "title-not-blank",
                "!has(self.title) || size(self.title) > 0", null));
        Receipt parties = reference.createParties();

        Problem problem = only(reference.reject(Transaction.by(PRINCIPAL)
                .relate(BETA_EMPLOYS_ALICE, "EmploysFullTime", parties.temporaryIds().get(BETA),
                        parties.temporaryIds().get(ALICE), from(3, 1), values("title", "")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(problem.message()).contains("title-not-blank");
    }

    // ------------------------------------------------------------------ a language that fails

    @Test
    void anEvaluationErrorIsAFailedCheckWithItsReason() {
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(accountChecked("evaluation error"),
                new StubLanguage());

        Problem problem = only(aboutChecks(reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", 1))
                .build())));

        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_FAILED);
        assertThat(problem.message()).contains("a-check").contains("The stub cannot evaluate this.");
        assertThat(problem.found()).contains("The stub cannot evaluate this.");
    }

    @Test
    void aLanguageThatThrowsWhileEvaluatingIsAFailedCheckToo() {
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(accountChecked("crash when evaluated"),
                new StubLanguage());

        Problem problem = only(aboutChecks(reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", 1))
                .build())));

        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_FAILED);
        assertThat(problem.message()).contains("The stub crashed while evaluating.");
    }

    @Test
    void theStoreSurvivesAFailedCheck() {
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(accountChecked("evaluation error"),
                new StubLanguage());
        reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", 1)).build());

        reference.commit(Transaction.by(PRINCIPAL).create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        assertThat(reference.view(on(2, 1)).entities("Person")).hasSize(1);
        assertThat(reference.view(on(2, 1)).entities("Account")).isEmpty();
    }
}
