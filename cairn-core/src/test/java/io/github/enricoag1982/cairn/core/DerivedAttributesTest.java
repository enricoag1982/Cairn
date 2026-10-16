package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How derived attributes are computed at commit, beyond the scenarios of the {@code derived-attributes} spec (design D3
 * and D4 of {@code add-derived-attributes}): required ones, writes that name them, which segments are computed, what
 * constraints, uniqueness and checks see, and what a failed derivation reports. Tests with real expressions use CEL from
 * the module {@code cairn-cel}; those about misbehaving languages use {@link StubLanguage}.
 */
class DerivedAttributesTest {

    private static final String LABEL = "self.number + ' ' + self.currency";
    private static final String CENTS = "int(self.balance * 100.0)";
    private static final TemporaryId BOB = new TemporaryId("bob");

    private static SchemaDefinition withLabel(boolean required) {
        return withDerivedAttribute(ReferenceModel.definition(), "Account", "label", ValueType.STRING, required, LABEL);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static Value valueOf(EntityState state, String attribute) {
        return state.values().get(attribute);
    }

    private static EntityState account(ReferenceStore reference, EntityId id, int month, int day) {
        return reference.view(on(month, day)).entity(id).orElseThrow();
    }

    private static Transaction createA1(Receipt parties, Object... extra) {
        List<Object> values = new ArrayList<>(List.of("number", "A-1", "currency", "EUR", "balance", money("1000")));
        values.addAll(List.of(extra));
        return Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values(values.toArray()))
                .relate(HELD_BY_A1, "HeldBy", A1, parties.temporaryIds().get(BETA), from(1, 1), values())
                .build();
    }

    // ------------------------------------------------------------------ required and written

    @Test
    void aCreateNeedsNoValueForARequiredDerivedAttribute() {
        ReferenceStore reference = ReferenceStore.with(withLabel(true));

        Receipt accounts = reference.createAccounts();

        assertThat(valueOf(account(reference, entity(accounts, A1), 3, 31), "label")).isEqualTo(Value.of("A-1 EUR"));
        assertThat(valueOf(account(reference, entity(accounts, A2), 3, 31), "label")).isEqualTo(Value.of("A-2 EUR"));
    }

    @Test
    void aReplaceNeedsNoValueForARequiredDerivedAttributeAndComputesItAgain() {
        ReferenceStore reference = ReferenceStore.with(withLabel(true));
        EntityId a1 = entity(reference.createAccounts(), A1);

        reference.commit(Transaction.by(PRINCIPAL).replace(a1, from(4, 1),
                values("number", "A-1", "currency", "USD", "balance", money("900"))).build());

        assertThat(valueOf(account(reference, a1, 3, 31), "label")).isEqualTo(Value.of("A-1 EUR"));
        assertThat(valueOf(account(reference, a1, 4, 30), "label")).isEqualTo(Value.of("A-1 USD"));
    }

    @Test
    void aCreateThatSetsADerivedAttributeIsRejectedAtTheValueAndNothingElseIsReported() {
        ReferenceStore reference = ReferenceStore.with(withLabel(true));
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties, "label", "mine"));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
        assertThat(problem.path()).isEqualTo("$a1.label");
        assertThat(problem.message()).contains("label").contains("Account").contains("derived");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Leave label out"));
    }

    @Test
    void aPatchThatSetsOrClearsADerivedAttributeIsRejected() {
        ReferenceStore reference = ReferenceStore.with(withLabel(false));
        EntityId a1 = entity(reference.createAccounts(), A1);

        List<Problem> sets = reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("label", "mine", "balance", 5)).build());
        List<Problem> clears = reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values(), Set.of("label"), ConflictCheck.none()).build());

        assertThat(sets).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
            assertThat(problem.path()).isEqualTo(a1.value() + ".label");
        });
        assertThat(clears).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
            assertThat(problem.path()).isEqualTo(a1.value() + ".label");
        });
        assertThat(valueOf(account(reference, a1, 4, 30), "label")).as("nothing was written").isEqualTo(Value.of("A-1 EUR"));
    }

    @Test
    void aReplaceThatSetsADerivedAttributeIsRejected() {
        ReferenceStore reference = ReferenceStore.with(withLabel(true));
        EntityId a1 = entity(reference.createAccounts(), A1);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL).replace(a1, from(4, 1),
                values("number", "A-1", "currency", "EUR", "balance", 5, "label", "mine")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
        assertThat(problem.path()).isEqualTo(a1.value() + ".label");
    }

    @Test
    void aDerivedAttributeIsAlsoWrittenByAliasAndInAnyCapitalization() {
        ReferenceStore reference = ReferenceStore.with(withLabel(false));
        EntityId a1 = entity(reference.createAccounts(), A1);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("LABEL", "mine")).build());

        assertThat(only(problems).code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
    }

    @Test
    void aWriteToARelationshipThatSetsADerivedAttributeIsRejected() {
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Employs", "heading",
                ValueType.STRING, false, "has(self.title) ? self.title : 'none'");
        ReferenceStore reference = ReferenceStore.with(model);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .relate(BETA_EMPLOYS_ALICE, "Employs", parties.temporaryIds().get(BETA),
                        parties.temporaryIds().get(ALICE), from(3, 1), values("heading", "mine")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
        assertThat(problem.path()).isEqualTo("$betaEmploysAlice.heading");
    }

    // ------------------------------------------------------------------ relationships and the segments computed

    @Test
    void aRelationshipTypeHasDerivedAttributesToo() {
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Employs", "heading",
                ValueType.STRING, true, "has(self.title) ? self.title : 'none'");
        ReferenceStore reference = ReferenceStore.with(model);
        Receipt parties = reference.createParties();

        Receipt employs = reference.commit(Transaction.by(PRINCIPAL)
                .relate(BETA_EMPLOYS_ALICE, "Employs", parties.temporaryIds().get(BETA),
                        parties.temporaryIds().get(ALICE), from(3, 1), values("title", "Analyst")).build());
        RelationshipId relationship = ReferenceStore.relationship(employs, BETA_EMPLOYS_ALICE);
        reference.commit(Transaction.by(PRINCIPAL).patch(relationship, from(5, 1), values("title", "Lead")).build());

        RelationshipState april = reference.view(on(4, 1)).relationship(relationship).orElseThrow();
        RelationshipState june = reference.view(on(6, 1)).relationship(relationship).orElseThrow();
        assertThat(april.values().get("heading")).isEqualTo(Value.of("Analyst"));
        assertThat(june.values().get("heading")).isEqualTo(Value.of("Lead"));
    }

    @Test
    void onlyTheSegmentsThatAWriteChangesAreComputedAgain() {
        StubLanguage language = new StubLanguage();
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(withLabel(false), language);
        EntityId a1 = entity(reference.createAccounts(), A1);
        int afterCreation = language.evaluations();
        assertThat(afterCreation).as("one for each account").isEqualTo(2);

        reference.commit(Transaction.by(PRINCIPAL)
                .patch(a1, EffectivePeriod.between(on(3, 1), on(4, 1)), values("balance", money("1500"))).build());

        assertThat(language.evaluations() - afterCreation).as("one for the changed segment of three").isEqualTo(1);
    }

    @Test
    void aWriteThatLeavesTheValuesAsTheyWereComputesNothing() {
        StubLanguage language = new StubLanguage();
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(withLabel(false), language);
        EntityId a1 = entity(reference.createAccounts(), A1);
        int afterCreation = language.evaluations();

        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(4, 1), values("balance", money("1000"))).build());

        assertThat(language.evaluations()).isEqualTo(afterCreation);
    }

    @Test
    void aPatchComputesOnlyTheSegmentsInsideItsPeriod() {
        ReferenceStore reference = ReferenceStore.with(withDerivedAttribute(ReferenceModel.definition(), "Account",
                "balanceInCents", ValueType.LONG, false, CENTS));
        EntityId a1 = entity(reference.createAccounts(), A1);

        reference.commit(Transaction.by(PRINCIPAL)
                .patch(a1, EffectivePeriod.between(on(3, 1), on(4, 1)), values("balance", money("1500"))).build());

        assertThat(valueOf(account(reference, a1, 2, 15), "balanceInCents")).isEqualTo(Value.of(100_000L));
        assertThat(valueOf(account(reference, a1, 3, 15), "balanceInCents")).isEqualTo(Value.of(150_000L));
        assertThat(valueOf(account(reference, a1, 4, 15), "balanceInCents")).isEqualTo(Value.of(100_000L));
    }

    @Test
    void aSegmentThatACascadeOnlyCutsKeepsItsDerivedValuesAndIsNotComputed() {
        StubLanguage language = new StubLanguage();
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Address", "line",
                ValueType.STRING, true, "self.street");
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(model, language);
        Receipt parties = reference.createParties();
        Receipt address = reference.commit(Transaction.by(PRINCIPAL)
                .create(new TemporaryId("home"), "Address", from(1, 1),
                        values("street", "1 Main Street", "city", "Berlin", "country", "DE"))
                .relate(new TemporaryId("hasHome"), "HasAddress", parties.temporaryIds().get(ALICE),
                        new TemporaryId("home"), from(1, 1), values()).build());
        EntityId home = entity(address, new TemporaryId("home"));
        int afterCreation = language.evaluations();

        reference.commit(Transaction.by(PRINCIPAL).endDate(entity(parties, ALICE), on(7, 1)).build());

        assertThat(language.evaluations()).as("ending Alice ends her address, which is not computed again")
                .isEqualTo(afterCreation);
        assertThat(valueOf(account(reference, home, 6, 1), "line")).isEqualTo(Value.of("stub"));
        assertThat(reference.view(on(7, 2)).entity(home)).as("the address ended with its owner").isEmpty();
    }

    @Test
    void aDryRunComputesAndReportsTheSame() {
        ReferenceStore reference = ReferenceStore.with(withDerivedAttribute(ReferenceModel.definition(), "Account",
                "balanceInBillions", ValueType.INT, false, "int(self.balance) * 10000000"));
        Receipt parties = reference.createParties();
        Transaction transaction = createA1(parties);

        DryRunResult dryRun = reference.store.dryRun(transaction);
        CommitResult commit = reference.store.commit(transaction);

        assertThat(dryRun).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dryRun).problems())
                .isEqualTo(((CommitResult.Rejected) commit).problems());
        assertThat(((DryRunResult.Rejected) dryRun).problems()).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.DERIVATION_FAILED));
    }

    @Test
    void aDryRunThatWouldCommitWritesNothing() {
        ReferenceStore reference = ReferenceStore.with(withLabel(true));
        Receipt parties = reference.createParties();

        DryRunResult dryRun = reference.store.dryRun(createA1(parties));

        assertThat(dryRun).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.view(on(3, 31)).entities("Account")).isEmpty();
    }

    // ------------------------------------------------------------------ what sees the derived values

    @Test
    void constraintsApplyToTheComputedValue() {
        SchemaDefinition model = changeEntityType(ReferenceModel.definition(), "Account", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder("code", ValueType.STRING).derived(LABEL).maxLength(5).build());
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(), attributes,
                    type.aliases(), type.deprecated(), type.description(), type.rules(), type.checks());
        });
        ReferenceStore reference = ReferenceStore.with(model);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_LENGTH);
        assertThat(problem.path()).isEqualTo("$a1.code");
    }

    @Test
    void uniquenessSeesTheComputedValue() {
        SchemaDefinition model = changeEntityType(ReferenceModel.definition(), "Account", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder("currencyCode", ValueType.STRING).derived("self.currency")
                    .unique().build());
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(), attributes,
                    type.aliases(), type.deprecated(), type.description(), type.rules(), type.checks());
        });
        ReferenceStore reference = ReferenceStore.with(model);
        Receipt parties = reference.createParties();
        reference.commit(createA1(parties));

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(A2, "Account", from(1, 1), values("number", "A-2", "currency", "EUR", "balance", money("5")))
                .relate(ReferenceStories.HELD_BY_A2, "HeldBy", A2, parties.temporaryIds().get(ACME), from(1, 1),
                        values()).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_VALUE_TAKEN);
        assertThat(problem.path()).isEqualTo("$a2.currencyCode");
    }

    @Test
    void checksSeeTheComputedValue() {
        ReferenceStore reference = ReferenceStore.with(withCheck(withLabel(false), "Account", "label-is-not-a1",
                "self.label != 'A-1 EUR'", "The label is A-1 EUR."));
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(problem.message()).contains("label-is-not-a1");
    }

    // ------------------------------------------------------------------ failures

    @Test
    void aFailedDerivationOfARequiredAttributeIsReportedAloneWithoutFollowUpProblems() {
        SchemaDefinition model = withCheck(withDerivedAttribute(ReferenceModel.definition(), "Person", "birthYearText",
                ValueType.STRING, true, "string(self.birthDate.getFullYear())"), "Person", "year-set",
                "has(self.birthYearText)", null);
        ReferenceStore reference = ReferenceStore.with(model);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).as("neither a missing required value nor a failed check").isEqualTo(
                ProblemCode.DERIVATION_FAILED);
        assertThat(problem.path()).isEqualTo("$alice.birthYearText");
        assertThat(problem.message()).contains("birthYearText").contains("birthDate");
    }

    @Test
    void noValueForARequiredDerivedAttributeIsAFailure() {
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Person", "birthYear",
                ValueType.INT, true, "has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null");
        ReferenceStore reference = ReferenceStore.with(model);

        List<Problem> alice = reference.reject(Transaction.by(PRINCIPAL)
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());
        Receipt bob = reference.commit(Transaction.by(PRINCIPAL)
                .create(BOB, "Person", from(1, 1), values("name", "Bob", "birthDate", LocalDate.of(1990, 5, 1)))
                .build());

        Problem problem = only(alice);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVATION_FAILED);
        assertThat(problem.path()).isEqualTo("$alice.birthYear");
        assertThat(problem.message()).contains("required");
        assertThat(valueOf(reference.view(on(3, 31)).entity(entity(bob, BOB)).orElseThrow(), "birthYear"))
                .isEqualTo(Value.of(1990));
    }

    @Test
    void anOptionalDerivedAttributeThatGivesNoValueLosesItsEarlierValue() {
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Person", "birthYear",
                ValueType.INT, false, "has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null");
        ReferenceStore reference = ReferenceStore.with(model);
        Receipt bob = reference.commit(Transaction.by(PRINCIPAL)
                .create(BOB, "Person", from(1, 1), values("name", "Bob", "birthDate", LocalDate.of(1990, 5, 1)))
                .build());
        EntityId id = entity(bob, BOB);

        reference.commit(Transaction.by(PRINCIPAL).patch(id, from(6, 1), values(), Set.of("birthDate"),
                ConflictCheck.none()).build());

        assertThat(valueOf(reference.view(on(5, 1)).entity(id).orElseThrow(), "birthYear")).isEqualTo(Value.of(1990));
        assertThat(reference.view(on(7, 1)).entity(id).orElseThrow().values()).doesNotContainKey("birthYear");
    }

    @Test
    void aResultThatDoesNotFitTheAttributeIsAFailureEvenIfTheLanguageAcceptedIt() {
        StubLanguage language = new StubLanguage();
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
                ValueType.STRING, false, "wrong type");
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(model, language);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVATION_FAILED);
        assertThat(problem.message()).contains("does not fit").contains("string");
    }

    @Test
    void aLanguageThatFailsWhenEvaluatingIsAFailureOfTheDerivation() {
        for (String expression : List.of("evaluation error", "crash when evaluated")) {
            StubLanguage language = new StubLanguage();
            SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
                    ValueType.STRING, false, expression);
            ReferenceStore reference = ReferenceStore.withExpressionLanguage(model, language);
            Receipt parties = reference.createParties();

            List<Problem> problems = reference.reject(createA1(parties));

            Problem problem = only(problems);
            assertThat(problem.code()).as(expression).isEqualTo(ProblemCode.DERIVATION_FAILED);
            assertThat(problem.message()).as(expression).contains("label").contains("stub");
        }
    }

    @Test
    void everyFailingDerivationOfAnEntityIsReported() {
        SchemaDefinition model = withDerivedAttribute(withDerivedAttribute(ReferenceModel.definition(), "Account",
                "one", ValueType.STRING, false, "evaluation error"), "Account", "two", ValueType.STRING, false,
                "evaluation error");
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(model, new StubLanguage());
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties));

        assertThat(problems).extracting(Problem::path).containsExactly("$a1.one", "$a1.two");
    }

    @Test
    void aValueThatDoesNotFitItsAttributeIsNotAskedAboutAndHasItsOwnProblem() {
        StubLanguage language = new StubLanguage();
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(withLabel(true), language);
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(createA1(parties, "balance", "plenty"));

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.VALUE_TYPE_MISMATCH);
        assertThat(language.evaluations()).isZero();
    }

    // ------------------------------------------------------------------ after the change to a plain attribute

    @Test
    void aDerivedAttributeMadePlainKeepsItsStoredValuesAndIsNotComputedAnyMore() {
        ReferenceStore reference = ReferenceStore.with(withLabel(false));
        EntityId a1 = entity(reference.createAccounts(), A1);
        SchemaDefinition plain = changeEntityType(ReferenceModel.definition(), "Account", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder("label", ValueType.STRING).build());
            return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(), attributes,
                    type.aliases(), type.deprecated(), type.description(), type.rules(), type.checks());
        });
        reference.recordedOn(on(6, 1));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), plain))
                .isInstanceOf(RegistrationResult.Applied.class);

        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(6, 2), values("currency", "USD")).build());
        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(6, 3), values("label", "mine")).build());

        assertThat(valueOf(account(reference, a1, 6, 2), "label")).as("stored, and not computed again")
                .isEqualTo(Value.of("A-1 EUR"));
        assertThat(valueOf(account(reference, a1, 6, 3), "label")).as("a plain attribute takes writes")
                .isEqualTo(Value.of("mine"));
    }
}
