package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.onlyProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code attribute-access} spec that the engine shows, one test each: classified attributes,
 * views restricted to a clearance, queries and keys over withheld attributes, and commits with a clearance (ADR-0036). The
 * scenarios of the agent server are shown with the server, and those of documents and of schema evolution with the readers
 * and the registry. The classifications are test-only: Account's {@code balance} is {@code finance}, and Organization's
 * {@code lei} is {@code identity}. The scenarios that read and write data run against RocksDB stores too (see
 * {@link TestBackend}).
 */
class AttributeAccessScenariosTest {

    private static final String CENTS = "int(self.balance * 100.0)";

    /** The reference model in which Account's {@code balance} is classified {@code finance}. */
    private static SchemaDefinition finance() {
        return withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
    }

    /** The reference model in which Organization's {@code lei} is classified {@code identity}. */
    private static SchemaDefinition identity() {
        return withClassification(ReferenceModel.definition(), "Organization", "lei", "identity");
    }

    private static SchemaRegistry registry() {
        return SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(),
                Optional.of(ExpressionLanguages.discovered().orElseThrow()));
    }

    /** RD-1 and RD-5, as recorded, in a store whose schema classifies the balance; returns A-1's ID. */
    private static EntityId rd1AndRd5(ReferenceStore reference) {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        reference.replay(ReferenceStories.rd5BalanceCorrection(a1));
        return a1;
    }

    // ------------------------------------------------------------------ classified attributes

    @Test
    @DisplayName("Scenario: Classifying a balance")
    void classifyingABalance() {
        SchemaRegistry registry = registry();
        SchemaDefinition definition = finance();

        assertThat(registry.validate(ReferenceModel.origin("1.0.0"), definition)).isEmpty();

        Schema schema = applied(registry.register(ReferenceModel.origin("1.0.0"), definition)).schema();
        Attribute balance = schema.entityType("Account").orElseThrow().attribute("balance").orElseThrow();
        assertThat(balance.classification()).hasValue("finance");
        assertThat(schema.entityType("Account").orElseThrow().attribute("number").orElseThrow().classification())
                .as("an attribute without a classification").isEmpty();
    }

    @Test
    @DisplayName("Scenario: A derived attribute that would reveal a classified one")
    void aDerivedAttributeThatWouldRevealAClassifiedOne() {
        SchemaDefinition definition = withDerivedAttribute(finance(), "Account", "balanceInCents", ValueType.LONG, false,
                CENTS);

        List<Problem> problems = registry().validate(ReferenceModel.origin("1.0.0"), definition);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
            assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        });
    }

    @Test
    @DisplayName("Scenario: A malformed classification")
    void aMalformedClassification() {
        SchemaDefinition definition = withClassification(ReferenceModel.definition(), "Account", "balance",
                "Finance Data");

        List<Problem> problems = registry().validate(ReferenceModel.origin("1.0.0"), definition);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
            assertThat(problem.path()).isEqualTo("Account.balance");
        });
    }

    // ------------------------------------------------------------------ views restricted to a clearance

    @Test
    @DisplayName("Scenario: A balance withheld")
    void aBalanceWithheld() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);

        EntityState state = reference.view(on(4, 1)).restrictedTo(Clearance.none()).entity(a1).orElseThrow();

        assertThat(state.values()).containsOnlyKeys("number", "currency");
        assertThat(state.values()).containsEntry("number", Value.of("A-1")).containsEntry("currency", Value.of("EUR"));
        assertThat(state.withheld()).containsExactly("balance");
    }

    @Test
    @DisplayName("Scenario: A clearance that covers the balance")
    void aClearanceThatCoversTheBalance() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);

        EntityState state = reference.view(on(4, 1)).restrictedTo(Clearance.of("finance")).entity(a1).orElseThrow();

        assertThat(state.values()).containsEntry("balance", Value.of(money("1200")));
        assertThat(state.values()).containsKeys("number", "currency", "balance");
        assertThat(state.withheld()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Restricting a restricted view")
    void restrictingARestrictedView() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);
        View finance = reference.view(on(4, 1)).restrictedTo(Clearance.of("finance"));

        View both = finance.restrictedTo(Clearance.of("identity"));

        EntityState state = both.entity(a1).orElseThrow();
        assertThat(state.withheld()).containsExactly("balance");
        assertThat(state.values()).doesNotContainKey("balance");
        assertThat(both.clearance()).hasValue(Clearance.none());
        assertThat(finance.entity(a1).orElseThrow().withheld()).as("the view that was restricted is not changed")
                .isEmpty();
    }

    @Test
    @DisplayName("Scenario: Navigating to a withheld value")
    void navigatingToAWithheldValue() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);
        EntityId beta = entity(reference.parties, BETA);

        List<Link> links = reference.view(on(4, 1)).restrictedTo(Clearance.none()).navigate(beta, "holds");

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(a1);
            assertThat(link.entity().withheld()).containsExactly("balance");
            assertThat(link.entity().values()).doesNotContainKey("balance");
        });
    }

    // ------------------------------------------------------------------ queries and keys

    @Test
    @DisplayName("Scenario: Filtering on a withheld balance")
    void filteringOnAWithheldBalance() {
        ReferenceStore reference = ReferenceStore.with(finance());
        rd1AndRd5(reference);
        Query query = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(money("1000")))).build();

        Problem problem = onlyProblem(reference.view(on(4, 1)).restrictedTo(Clearance.none()).query(query));

        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).as("the filter, the first step of the query").isEqualTo("@query.steps.0");
    }

    @Test
    @DisplayName("Scenario: Summing withheld balances")
    void summingWithheldBalances() {
        ReferenceStore reference = ReferenceStore.with(finance());
        rd1AndRd5(reference);
        Query query = Query.from("Account", "a")
                .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total")).build();

        Problem problem = onlyProblem(reference.view(on(4, 1)).restrictedTo(Clearance.none()).query(query));

        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).as("the aggregate").isEqualTo("@query.aggregates.0");
    }

    @Test
    @DisplayName("Scenario: A key of a withheld attribute")
    void aKeyOfAWithheldAttribute() {
        ReferenceStore reference = ReferenceStore.with(identity());
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        UniqueKey key = new UniqueKey("Organization", "lei", Value.of(LEI));

        assertThat(reference.view(on(4, 1)).entity(key)).as("a view that is not restricted finds ACME")
                .map(EntityState::id).hasValue(acme);
        assertThat(reference.view(on(4, 1)).restrictedTo(Clearance.of("identity")).entity(key))
                .as("a view whose clearance covers the LEI").map(EntityState::id).hasValue(acme);
        assertThatThrownBy(() -> reference.view(on(4, 1)).restrictedTo(Clearance.none()).entity(key))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lei").hasMessageContaining("identity");
    }

    // ------------------------------------------------------------------ commits with a clearance

    @Test
    @DisplayName("Scenario: Writing a withheld balance")
    void writingAWithheldBalance() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);
        reference.recordedOn(on(5, 2));

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(a1, from(5, 1), values("balance", money("1500"))).build());

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).as("the balance of the write").isEqualTo(a1.value() + ".balance");
        });
        assertThat(reference.view(on(5, 1)).entity(a1).orElseThrow().values())
                .containsEntry("balance", Value.of(money("1200")));
    }

    @Test
    @DisplayName("Scenario: A patch that leaves a withheld balance")
    void aPatchThatLeavesAWithheldBalance() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = rd1AndRd5(reference);
        reference.recordedOn(on(5, 2));

        reference.commit(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(a1, from(5, 1), values("currency", "CHF")).build());

        EntityState state = reference.view(on(5, 1)).entity(a1).orElseThrow();
        assertThat(state.values()).containsEntry("currency", Value.of("CHF"))
                .containsEntry("balance", Value.of(money("1200")));
    }

    @Test
    @DisplayName("Scenario: Naming an entity by a withheld key")
    void namingAnEntityByAWithheldKey() {
        ReferenceStore reference = ReferenceStore.with(identity());
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 2));
        UniqueKey held = new UniqueKey("Organization", "lei", Value.of(LEI));
        UniqueKey nobodys = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R13"));

        List<Problem> forHeld = reference.reject(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(held, from(5, 1), values("name", "ACME Holdings")).build());
        List<Problem> forNobodys = reference.reject(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(nobodys, from(5, 1), values("name", "ACME Holdings")).build());

        assertThat(forHeld).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).as("the write's reference").isEqualTo("@write.0");
        });
        assertThat(forNobodys).as("the same problem for a value that no entity has").singleElement()
                .satisfies(problem -> {
                    assertThat(problem.code()).isEqualTo(forHeld.getFirst().code());
                    assertThat(problem.path()).isEqualTo(forHeld.getFirst().path());
                    assertThat(problem.expected()).isEqualTo(forHeld.getFirst().expected());
                    assertThat(problem.found()).isEqualTo(forHeld.getFirst().found());
                });
        assertThat(reference.view(on(5, 3)).entity(acme).orElseThrow().values()).containsEntry("name", Value.of("ACME"));
    }

    @Test
    @DisplayName("Scenario: A rule over a withheld value")
    void aRuleOverAWithheldValue() {
        ReferenceStore reference = ReferenceStore.withRules(
                withClassification(AccountFundedRule.model(), "Account", "balance", "finance"),
                ReferenceStore.rulesWith(new AccountFundedRule()), ReferenceModel.declaredRules());
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        reference.recordedOn(on(3, 2));
        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(3, 1), values("balance", money("900"))).build());
        reference.recordedOn(on(5, 2));
        EntityId acme = entity(reference.parties, ACME);
        EntityId alice = entity(reference.parties, ALICE);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .relate("Employs", acme, alice, from(5, 1), values("title", "Analyst")).build());

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
            assertThat(problem.ruleCode()).contains("account-a1-funded/underfunded");
        });
    }
}
