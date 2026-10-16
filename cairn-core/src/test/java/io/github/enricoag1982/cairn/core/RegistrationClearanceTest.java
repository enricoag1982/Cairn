package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.renamed;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.EntityTypeBuilder;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What a registration may change for a caller whose clearance limits it (ADR-0036, design D5 of
 * {@code add-attribute-access-control}): the registry rejects, with {@code ACCESS_DENIED} at the element, a change that adds,
 * changes or removes a classification that the clearance does not cover before or after, and one that adds a check or a
 * derived attribute, or changes the expression of one, that reads an attribute of such a classification, and then changes
 * nothing. A plan says the same as the registration. The tests run against RocksDB stores too.
 */
class RegistrationClearanceTest {

    private static final Clearance NONE = Clearance.none();
    private static final Clearance FINANCE = Clearance.of("finance");
    private static final Submission AGENT = Submission.by("agent:modeler");
    private static final String CENTS = "int(self.balance * 100.0)";

    private static SchemaDefinition plain() {
        return ReferenceModel.definition();
    }

    private static SchemaDefinition finance() {
        return withClassification(plain(), "Account", "balance", "finance");
    }

    private static ReferenceStore store(SchemaDefinition definition) {
        return ReferenceStore.withExpressionLanguage(definition, ExpressionLanguages.discovered().orElseThrow());
    }

    private static RegistrationResult register(ReferenceStore reference, SchemaDefinition definition,
            Clearance clearance) {
        reference.recordedOn(on(6, 1));
        return reference.store.schema().register(ReferenceModel.origin("1.1.0"), definition, AGENT, clearance);
    }

    /** The problem {@code ACCESS_DENIED} of a rejected registration, which must have exactly one. */
    private static Problem refused(RegistrationResult result) {
        assertThat(result).isInstanceOf(RegistrationResult.Rejected.class);
        List<Problem> problems = ((RegistrationResult.Rejected) result).problems();
        assertThat(problems).filteredOn(problem -> problem.code() == ProblemCode.ACCESS_DENIED).hasSize(1);
        return problems.stream().filter(problem -> problem.code() == ProblemCode.ACCESS_DENIED).findFirst()
                .orElseThrow();
    }

    /** A new subtype of Account, which has no entities yet, so that its checks and derived attributes are compatible. */
    private static SchemaDefinition withSavings(SchemaDefinition definition,
            UnaryOperator<EntityTypeBuilder> savings) {
        return Definitions.plus(definition, SchemaDefinition.builder()
                .entityType("Savings", type -> savings.apply(type.supertype("Account"))).build());
    }

    private static Optional<String> balanceClassification(ReferenceStore reference) {
        return reference.store.schema().current().entityType("Account").orElseThrow().attribute("balance")
                .orElseThrow().classification();
    }

    // ------------------------------------------------------------------ classifications

    @Test
    void aRegistrationThatRemovesAClassificationTheClearanceDoesNotCoverIsRefusedAndChangesNothing() {
        ReferenceStore reference = store(finance());
        int transactions = reference.store.schema().history().size();

        Problem problem = refused(register(reference, plain(), NONE));

        assertThat(problem.path()).isEqualTo("Account.balance");
        assertThat(problem.message()).contains("removes the classification finance");
        assertThat(problem.found()).hasValue("a clearance that covers no classification");
        assertThat(balanceClassification(reference)).hasValue("finance");
        assertThat(reference.store.schema().history()).hasSize(transactions);
    }

    @Test
    void aClearanceThatCoversTheClassificationMayRemoveIt() {
        ReferenceStore reference = store(finance());

        assertThat(register(reference, plain(), FINANCE)).isInstanceOf(RegistrationResult.Applied.class);

        assertThat(balanceClassification(reference)).isEmpty();
    }

    @Test
    void aRegistrationThatAddsAClassificationTheClearanceDoesNotCoverIsRefused() {
        ReferenceStore reference = store(plain());

        Problem problem = refused(register(reference, finance(), NONE));

        assertThat(problem.path()).isEqualTo("Account.balance");
        assertThat(problem.message()).contains("adds the classification finance");
        assertThat(balanceClassification(reference)).isEmpty();
        assertThat(register(reference, finance(), FINANCE)).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(balanceClassification(reference)).hasValue("finance");
    }

    @Test
    void aChangeOfClassificationNeedsBothTheOldAndTheNewCovered() {
        SchemaDefinition treasury = withClassification(plain(), "Account", "balance", "treasury");
        ReferenceStore reference = store(finance());

        Problem problem = refused(register(reference, treasury, FINANCE));

        assertThat(problem.message()).contains("changes the classification from finance to treasury");
        refused(register(reference, treasury, Clearance.of("treasury")));
        assertThat(balanceClassification(reference)).hasValue("finance");
        assertThat(register(reference, treasury, Clearance.of("finance", "treasury")))
                .isInstanceOf(RegistrationResult.Applied.class);
        assertThat(balanceClassification(reference)).hasValue("treasury");
    }

    @Test
    void aRegistrationThatLeavesTheClassificationsAloneIsAllowedWhateverTheyAre() {
        ReferenceStore reference = store(finance());
        SchemaDefinition withEmail = changeEntityType(finance(), "Person", person -> Definitions.withAttributes(person,
                append(person.attributes(), AttributeDefinition.builder("email", ValueType.STRING).build())));

        assertThat(register(reference, withEmail, NONE)).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(balanceClassification(reference)).hasValue("finance");
    }

    @Test
    void aRenameThatDropsTheClassificationIsAChangeOfIt() {
        ReferenceStore reference = store(finance());
        SchemaDefinition withAmount = changeEntityType(finance(), "Account", account -> changeAttribute(account, "balance",
                balance -> withoutClassification(renamed(balance, "amount", "balance"))));

        Problem problem = refused(register(reference, withAmount, NONE));

        assertThat(problem.path()).isEqualTo("Account.amount");
        assertThat(problem.message()).contains("removes the classification finance");
        assertThat(balanceClassification(reference)).hasValue("finance");
    }

    @Test
    void aRegistrationWithoutAClearanceAndAPlanWithoutOneAreNotLimited() {
        ReferenceStore reference = store(finance());
        reference.recordedOn(on(6, 1));

        SchemaPlan plan = reference.store.schema().plan(ReferenceModel.origin("1.1.0"), plain());
        RegistrationResult result = reference.store.schema().register(ReferenceModel.origin("1.1.0"), plain(), AGENT);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(result).isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void aPlanSaysWhatTheRegistrationWouldSay() {
        ReferenceStore reference = store(finance());

        SchemaPlan plan = reference.store.schema().plan(ReferenceModel.origin("1.1.0"), plain(), NONE);
        SchemaPlan covered = reference.store.schema().plan(ReferenceModel.origin("1.1.0"), plain(), FINANCE);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).isEqualTo("Account.balance");
        });
        assertThat(plan.changes()).as("the changes are listed too").isNotEmpty();
        assertThat(covered.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(balanceClassification(reference)).hasValue("finance");
    }

    // ------------------------------------------------------------------ checks

    @Test
    void aCheckAddedToATypeThatReadsAnUncoveredAttributeIsRefusedAtTheCheck() {
        ReferenceStore reference = store(finance());
        SchemaDefinition checked = withCheck(finance(), "Account", "balance-above-1000", "self.balance > 1000.0", null);

        RegistrationResult result = register(reference, checked, NONE);

        Problem problem = refused(result);
        assertThat(problem.path()).isEqualTo("Account.balance-above-1000");
        assertThat(problem.message()).contains("balance-above-1000").contains("balance").contains("finance");
        assertThat(reference.store.schema().current().entityType("Account").orElseThrow().effectiveChecks()).isEmpty();
    }

    @Test
    void aCheckOfANewTypeThatReadsAnUncoveredAttributeIsRefusedEvenWhereItIsCompatible() {
        ReferenceStore reference = store(finance());
        SchemaDefinition checked = withSavings(finance(),
                savings -> savings.check("savings-above-1000", "self.balance > 1000.0"));

        Problem problem = refused(register(reference, checked, NONE));

        assertThat(problem.path()).isEqualTo("Savings.savings-above-1000");
        assertThat(((RegistrationResult.Rejected) register(reference, checked, NONE)).problems())
                .as("it is the only problem: the change is compatible").hasSize(1);
        assertThat(reference.store.schema().current().entityType("Savings")).isEmpty();
        assertThat(register(reference, checked, FINANCE)).isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void aCheckThatReadsOnlyWhatTheClearanceCoversIsAllowed() {
        ReferenceStore reference = store(finance());
        SchemaDefinition checked = withSavings(finance(), savings -> savings.check("number-set", "has(self.number)"));

        assertThat(register(reference, checked, NONE)).isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void aCheckThatAnEarlierVersionHasIsNotAddedAgainByANewSubtype() {
        SchemaDefinition checked = withCheck(finance(), "Account", "balance-above-1000", "self.balance > 1000.0", null);
        ReferenceStore reference = store(checked);

        assertThat(register(reference, withSavings(checked, savings -> savings), NONE))
                .as("Savings inherits the check; it does not add it").isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void changingTheExpressionOfACheckThatReadsAnUncoveredAttributeIsRefusedAtTheCheck() {
        SchemaDefinition checked = withCheck(finance(), "Account", "balance-above-1000", "self.balance > 1000.0", null);
        SchemaDefinition changed = withCheck(finance(), "Account", "balance-above-1000", "self.balance > 5.0", null);
        ReferenceStore reference = store(checked);

        Problem problem = refused(register(reference, changed, NONE));

        assertThat(problem.path()).isEqualTo("Account.balance-above-1000");
        assertThat(problem.message()).contains("changes the check");
    }

    // ------------------------------------------------------------------ derived attributes

    private static SchemaDefinition derived(String expression) {
        return withClassification(withDerivedAttribute(finance(), "Account", "balanceInCents", ValueType.LONG, false,
                expression), "Account", "balanceInCents", "finance");
    }

    @Test
    void aDerivedAttributeAddedToATypeThatReadsAnUncoveredAttributeIsRefusedAtTheAttribute() {
        ReferenceStore reference = store(finance());

        Problem problem = refused(register(reference, derived(CENTS), NONE));

        assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        assertThat(reference.store.schema().current().entityType("Account").orElseThrow().attribute("balanceInCents"))
                .isEmpty();
    }

    @Test
    void aDerivedAttributeOfANewTypeIsAllowedToACovered() {
        ReferenceStore reference = store(finance());
        SchemaDefinition withCents = withSavings(finance(), savings -> savings.attribute("savingsCents",
                ValueType.LONG, attribute -> attribute.derived(CENTS).classification("finance")));

        Problem problem = refused(register(reference, withCents, NONE));

        assertThat(problem.path()).isEqualTo("Savings.savingsCents");
        assertThat(register(reference, withCents, FINANCE)).isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void changingTheExpressionOfADerivedAttributeThatReadsAnUncoveredAttributeIsRefused() {
        ReferenceStore reference = store(derived(CENTS));

        Problem problem = refused(register(reference, derived("int(self.balance * 1000.0)"), NONE));

        assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        assertThat(problem.message()).contains("changes the expression");
        assertThat(register(reference, derived(CENTS), NONE)).as("the same expression is not a change")
                .isInstanceOf(RegistrationResult.Unchanged.class);
    }

    private static List<AttributeDefinition> append(List<AttributeDefinition> attributes, AttributeDefinition added) {
        List<AttributeDefinition> all = new ArrayList<>(attributes);
        all.add(added);
        return all;
    }

    private static AttributeDefinition withoutClassification(AttributeDefinition attribute) {
        return new AttributeDefinition(attribute.name(), attribute.valueType(), attribute.required(), attribute.unique(),
                attribute.range(), attribute.aliases(), attribute.deprecated(), attribute.description(),
                attribute.examples(), attribute.allowedValues(), attribute.pattern(), attribute.minLength(),
                attribute.maxLength(), attribute.derived(), Optional.empty());
    }
}
