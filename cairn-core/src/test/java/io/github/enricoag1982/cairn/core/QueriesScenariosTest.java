package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.decimal;
import static io.github.enricoag1982.cairn.core.QueryRows.name;
import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.onlyProblem;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code queries} spec that a query built in Java can show, one test each. The two scenarios of the
 * JSON form are shown with the reader of queries.
 */
class QueriesScenariosTest {

    private final ReferenceStore reference = new ReferenceStore();

    /** The query of RD-6: the total balance of accounts by ultimate parent. */
    static Query totalBalanceByUltimateParent() {
        return Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .traverse("holder", "ownedBy", 0, 10, "party")
                .where(Condition.not(Condition.hasLink("party", "ownedBy")))
                .groupBy("party")
                .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
                .build();
    }

    private static Condition nameIs(String binding, String name) {
        return Condition.eq(Operand.attribute(binding, "name"), Operand.literal(name));
    }

    /** RD-1, RD-2 and RD-5 as they were recorded, in the order of their dates. */
    private void recordRd1Rd2Rd5() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(acme, beta));
        reference.replay(ReferenceStories.rd5BalancePatch(entity(accounts, A1)));
        reference.replay(ReferenceStories.rd5BalanceCorrection(entity(accounts, A1)));
        reference.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
    }

    /** A view effective 31 March as known on 5 April, before the corrections of RD-5 and RD-2. */
    private View march31KnownOnApril5() {
        return reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5)));
    }

    // ------------------------------------------------------------------ queries

    @Test
    @DisplayName("Scenario: Parties and organizations")
    void partiesAndOrganizations() {
        reference.createParties();
        View view = reference.view(on(3, 31));

        QueryResult.Rows parties = rows(view.query(Query.from("Party", "p").build()));
        QueryResult.Rows organizations = rows(view.query(Query.from("Organization", "o").build()));

        assertThat(names(parties, "p")).containsExactly("ACME", "Beta", "Alice");
        assertThat(names(organizations, "o")).containsExactly("ACME", "Beta");
    }

    @Test
    @DisplayName("Scenario: The exact type")
    void theExactType() {
        reference.createParties();

        QueryResult.Rows result = rows(reference.view(on(3, 31)).query(Query.from("Party", "p").exactType().build()));

        assertThat(result.rows()).as("Party is abstract").isEmpty();
        assertThat(result.columns()).containsExactly("p");
    }

    // ------------------------------------------------------------------ filters

    @Test
    @DisplayName("Scenario: Accounts above a balance")
    void accountsAboveABalance() {
        recordRd1Rd2Rd5();
        Query query = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(1200)))
                .build();

        QueryResult.Rows knownOnApril5 = rows(march31KnownOnApril5().query(query));
        QueryResult.Rows knownNow = rows(reference.view(on(3, 31)).query(query));

        assertThat(knownOnApril5.rows()).extracting(row -> QueryRows.text(row.entity("a").orElseThrow(), "number"))
                .containsExactly("A-1", "A-2");
        assertThat(knownNow.rows()).extracting(row -> QueryRows.text(row.entity("a").orElseThrow(), "number"))
                .containsExactly("A-2");
    }

    @Test
    @DisplayName("Scenario: Persons without a birth date")
    void personsWithoutABirthDate() {
        reference.createParties();
        Query query = Query.from("Person", "p").where(Condition.not(Condition.hasValue("p", "birthDate"))).build();

        QueryResult.Rows result = rows(reference.view(on(3, 31)).query(query));

        assertThat(names(result, "p")).containsExactly("Alice");
    }

    // ------------------------------------------------------------------ follow

    @Test
    @DisplayName("Scenario: Who owns Beta, with the share")
    void whoOwnsBetaWithTheShare() {
        Receipt parties = reference.createParties();
        Receipt ownership = reference.replay(
                ReferenceStories.rd2Ownership(entity(parties, ACME), entity(parties, BETA)));
        reference.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
        Query query = Query.from("Organization", "o")
                .where(nameIs("o", "Beta"))
                .follow("o", "ownedBy", "owner", "owns")
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 1)).query(query));

        assertThat(result.columns()).containsExactly("o", "owner", "owns");
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "owner")).isEqualTo("ACME");
            RelationshipState owns = row.relationship("owns").orElseThrow();
            assertThat(owns.id()).isEqualTo(relationship(ownership, ACME_OWNS_BETA));
            assertThat(owns.values().get("share")).isInstanceOfSatisfying(Value.DecimalValue.class,
                    share -> assertThat(share.value()).isEqualByComparingTo("55"));
        });
    }

    @Test
    @DisplayName("Scenario: Organizations with their employees, if any")
    void organizationsWithTheirEmployeesIfAny() {
        Receipt parties = reference.createParties();
        reference.replay(ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        Query query = Query.from("Organization", "o").optionalFollow("o", "Employs", "employee").build();

        QueryResult.Rows result = rows(reference.view(on(4, 1)).query(query));

        assertThat(result.rows()).extracting(row -> name(row, "o"), row -> name(row, "employee"))
                .containsExactly(tuple("ACME", null), tuple("Beta", "Alice"));
        assertThat(result.rows().getFirst().entity("employee")).isEmpty();
    }

    // ------------------------------------------------------------------ traversal

    @Test
    @DisplayName("Scenario: Beta and its owners")
    void betaAndItsOwners() {
        Receipt parties = reference.createParties();
        reference.replay(ReferenceStories.rd2Ownership(entity(parties, ACME), entity(parties, BETA)));
        Query query = Query.from("Organization", "o")
                .where(nameIs("o", "Beta"))
                .traverse("o", "ownedBy", 0, 5, "owner", Optional.empty(), Optional.of("depth"))
                .orderBy(Order.ascending(Column.depth("depth")))
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 1)).query(query));

        assertThat(result.columns()).containsExactly("o", "owner", "depth");
        assertThat(result.rows()).extracting(row -> name(row, "owner"), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple("Beta", Value.of(0)), tuple("ACME", Value.of(1)));
    }

    // ------------------------------------------------------------------ grouping

    @Test
    @DisplayName("Scenario: Total balance by ultimate parent")
    void totalBalanceByUltimateParentScenario() {
        recordRd1Rd2Rd5();
        Query query = totalBalanceByUltimateParent();

        QueryResult.Rows knownOnApril5 = rows(march31KnownOnApril5().query(query));
        QueryResult.Rows knownNow = rows(reference.view(on(3, 31)).query(query));

        assertThat(knownOnApril5.columns()).containsExactly("party", "total");
        assertThat(knownOnApril5.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("ACME");
            assertThat(decimal(row, "total")).isEqualByComparingTo("3500");
        });
        assertThat(knownNow.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("ACME");
            assertThat(decimal(row, "total")).isEqualByComparingTo("3200");
        });
    }

    @Test
    @DisplayName("Scenario: Counting no rows")
    void countingNoRows() {
        reference.createParties();
        Query query = Query.from("Person", "p")
                .where(nameIs("p", "Bob"))
                .aggregate(Aggregate.count("count"))
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 31)).query(query));

        assertThat(result.columns()).containsExactly("count");
        assertThat(result.rows()).singleElement()
                .satisfies(row -> assertThat(row.value("count")).contains(Value.of(0L)));
    }

    // ------------------------------------------------------------------ results

    @Test
    @DisplayName("Scenario: The account with the largest balance")
    void theAccountWithTheLargestBalance() {
        recordRd1Rd2Rd5();
        Query query = Query.from("Account", "a")
                .orderBy(Order.descending(Column.attribute("a", "balance")))
                .limit(1)
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 31)).query(query));

        assertThat(result.rows()).singleElement()
                .satisfies(row -> assertThat(QueryRows.text(row.entity("a").orElseThrow(), "number")).isEqualTo("A-2"));
        assertThat(result.effectiveAt()).as("the coordinates").isEqualTo(on(3, 31));
        assertThat(result.basis()).isEqualTo(reference.store.latestBasis());
    }

    // ------------------------------------------------------------------ invalid queries

    @Test
    @DisplayName("Scenario: A misspelled attribute")
    void aMisspelledAttribute() {
        recordRd1Rd2Rd5();
        Query query = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balanse"), Operand.literal(1200)))
                .build();

        QueryResult result = reference.view(on(3, 31)).query(query);

        assertThat(result).isInstanceOf(QueryResult.Invalid.class);
        Problem problem = onlyProblem(result);
        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(problem.path()).isEqualTo("@query.steps.0");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("balance"));
    }

    @Test
    @DisplayName("Scenario: Summing names")
    void summingNames() {
        reference.createParties();
        Query query = Query.from("Party", "p")
                .aggregate(Aggregate.sum(Operand.attribute("p", "name"), "total"))
                .build();

        QueryResult result = reference.view(on(3, 31)).query(query);

        Problem problem = onlyProblem(result);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_AGGREGATE);
        assertThat(problem.path()).isEqualTo("@query.aggregates.0");
        assertThat(problem.message()).contains("sum needs numbers");
    }
}
