package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.decimal;
import static io.github.enricoag1982.cairn.core.QueryRows.name;
import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.AttributeRef;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.NumericAttributeRef;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.OrderedAttributeRef;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Typed attribute references (design D6 of {@code add-queries-and-aggregation}) build the same queries as the untyped
 * builder, and a view returns the same rows for them: some reference queries, built both ways.
 */
class TypedQueriesTest {

    private static final OrderedAttributeRef<String> NAME = AttributeRef.string("name");
    private static final OrderedAttributeRef<LocalDate> BIRTH_DATE = AttributeRef.date("birthDate");
    private static final NumericAttributeRef<BigDecimal> BALANCE = AttributeRef.decimal("balance");
    private static final NumericAttributeRef<BigDecimal> SHARE = AttributeRef.decimal("share");

    private final ReferenceStore reference = new ReferenceStore();

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

    private View view(Coordinates coordinates) {
        return reference.store.view(coordinates);
    }

    @Test
    void theTotalBalanceByUltimateParentIsTheSameQueryWithATypedReference() {
        recordRd1Rd2Rd5();
        Query typed = Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .traverse("holder", "ownedBy", 0, 10, "party")
                .where(Condition.not(Condition.hasLink("party", "ownedBy")))
                .groupBy("party")
                .aggregate(BALANCE.of("a").sum("total"))
                .build();
        Query untyped = QueriesScenariosTest.totalBalanceByUltimateParent();

        assertThat(typed).isEqualTo(untyped);
        for (Coordinates coordinates : new Coordinates[] {Coordinates.effectiveAt(on(3, 31)),
                Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5))}) {
            assertThat(view(coordinates).query(typed)).isEqualTo(view(coordinates).query(untyped));
        }
        QueryResult.Rows now = rows(view(Coordinates.effectiveAt(on(3, 31))).query(typed));
        assertThat(now.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("ACME");
            assertThat(decimal(row, "total")).isEqualByComparingTo("3200");
        });
    }

    @Test
    void accountsAboveABalanceWithATypedCondition() {
        recordRd1Rd2Rd5();
        Query typed = Query.from("Account", "a").where(BALANCE.of("a").gt(new BigDecimal("1200"))).build();
        Query untyped = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(new BigDecimal("1200"))))
                .build();
        Query withAnInt = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(1200))).build();

        assertThat(typed).isEqualTo(untyped);
        Coordinates onApril5 = Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5));
        Coordinates now = Coordinates.effectiveAt(on(3, 31));
        assertThat(view(onApril5).query(typed)).isEqualTo(view(onApril5).query(withAnInt));
        assertThat(rows(view(onApril5).query(typed)).rows()).hasSize(2);
        assertThat(rows(view(now).query(typed)).rows()).hasSize(1);
        assertThat(view(now).query(typed)).isEqualTo(view(now).query(withAnInt));
    }

    @Test
    void theAccountWithTheLargestBalanceWithATypedOrder() {
        recordRd1Rd2Rd5();
        Query typed = Query.from("Account", "a").orderBy(BALANCE.of("a").descending()).limit(1).build();
        Query untyped = Query.from("Account", "a")
                .orderBy(Order.descending(Column.attribute("a", "balance")))
                .limit(1).build();

        assertThat(typed).isEqualTo(untyped);
        QueryResult.Rows result = rows(view(Coordinates.effectiveAt(on(3, 31))).query(typed));
        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(
                QueryRows.text(row.entity("a").orElseThrow(), "number")).isEqualTo("A-2"));
    }

    @Test
    void personsWithoutABirthDateWithATypedTest() {
        reference.createParties();
        Query typed = Query.from("Person", "p").where(Condition.not(BIRTH_DATE.of("p").hasValue())).build();
        Query untyped = Query.from("Person", "p").where(Condition.not(Condition.hasValue("p", "birthDate"))).build();

        assertThat(typed).isEqualTo(untyped);
        assertThat(names(rows(reference.view(on(3, 31)).query(typed)), "p")).containsExactly("Alice");
    }

    @Test
    void whoOwnsBetaWithTheShareWithTypedReferences() {
        Receipt parties = reference.createParties();
        Receipt ownership = reference.replay(
                ReferenceStories.rd2Ownership(entity(parties, ACME), entity(parties, BETA)));
        reference.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
        Query typed = Query.from("Organization", "o")
                .where(NAME.of("o").eq("Beta"))
                .follow("o", "ownedBy", "owner", "owns")
                .where(SHARE.of("owns").gt(new BigDecimal("50")))
                .select(NAME.of("owner").column("owner"), SHARE.of("owns").column("share"))
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 1)).query(typed));

        assertThat(result.columns()).containsExactly("owner", "share");
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.value("owner")).contains(Value.of("ACME"));
            assertThat(decimal(row, "share")).isEqualByComparingTo("55");
        });
    }

    @Test
    void aTypedReferenceToAnAttributeThatDoesNotExistIsAProblemOfTheView() {
        reference.createParties();
        Query typed = Query.from("Person", "p").where(AttributeRef.date("birthdate_").of("p").hasValue()).build();

        QueryResult result = reference.view(on(3, 31)).query(typed);

        Problem problem = QueryRows.onlyProblem(result);
        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(problem.path()).isEqualTo("@query.steps.0");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("birthDate"));
    }
}
