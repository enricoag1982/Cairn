package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.problems;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Traversal;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Queries through views restricted to a clearance, beyond the scenarios of the {@code attribute-access} spec (ADR-0036,
 * design D3 of {@code add-attribute-access-control}): every place where a query can name an attribute is refused when the
 * attribute is withheld, with the place in the path of the problem, and nothing is read. The world is the reference domain
 * with RD-1, RD-2, RD-5 and the LEI of RD-9, with the classifications of {@link ClassifiedModel}. The tests run against
 * RocksDB stores too (see {@link TestBackend}).
 */
class RestrictedQueriesTest {

    private static final Operand.AttributeOf BALANCE = Operand.attribute("a", "balance");
    private static final Operand SHARE = Operand.attribute("owns", "share");

    private final ReferenceStore reference = ReferenceStore.with(ClassifiedModel.definition());
    private EntityId a1;

    @BeforeEach
    void theWorldOfRd1Rd2Rd5AndTheLei() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        a1 = entity(accounts, A1);
        reference.replay(ReferenceStories.rd2Ownership(acme, beta));
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
    }

    private View view() {
        return reference.view(on(4, 1));
    }

    /** A place where a query names a withheld attribute: what it is, the query, and the path of the problem. */
    private static Arguments place(String name, Query query, String path, String classification) {
        return Arguments.of(name, query, path, classification);
    }

    static Stream<Arguments> places() {
        return Stream.of(
                place("a comparison in a filter",
                        Query.from("Account", "a").where(Condition.gt(BALANCE, Operand.literal(money("1000")))).build(),
                        "@query.steps.0", "finance"),
                place("a literal on the left of a comparison",
                        Query.from("Account", "a").where(Condition.lt(Operand.literal(money("1000")), BALANCE)).build(),
                        "@query.steps.0", "finance"),
                place("a test for a value",
                        Query.from("Organization", "a").where(Condition.hasValue("a", "lei")).build(),
                        "@query.steps.0", "identity"),
                place("a list of values",
                        Query.from("Account", "a").where(Condition.in(BALANCE, Value.of(money("1000")))).build(),
                        "@query.steps.0", "finance"),
                place("a condition inside a negation, a disjunction and a conjunction",
                        Query.from("Account", "a").where(Condition.and(Condition.eq(Operand.attribute("a", "number"),
                                Operand.literal("A-1")), Condition.or(Condition.eq(Operand.attribute("a", "number"),
                                Operand.literal("A-2")), Condition.not(Condition.ge(BALANCE,
                                Operand.literal(money("1")))))))
                                .build(), "@query.steps.0", "finance"),
                place("the second filter of a query",
                        Query.from("Account", "a").where(Condition.eq(Operand.attribute("a", "number"),
                                Operand.literal("A-1"))).where(Condition.gt(BALANCE, Operand.literal(money("0"))))
                                .build(), "@query.steps.1", "finance"),
                place("an attribute of a relationship in a filter",
                        Query.from("Organization", "o").follow("o", "Owns", "p", "owns")
                                .where(Condition.gt(SHARE, Operand.literal(50))).build(), "@query.steps.1",
                        "ownership"),
                place("an attribute of the entity at the other end in a filter",
                        Query.from("Party", "p").follow("p", "ownedBy", "o")
                                .where(Condition.hasValue("o", "lei")).build(), "@query.steps.1", "identity"),
                place("an order",
                        Query.from("Account", "a").orderBy(Order.ascending(Column.attribute("a", "balance"))).build(),
                        "@query.order.0", "finance"),
                place("a group key",
                        Query.from("Account", "a").groupBy(GroupKey.attribute("a", "balance"))
                                .aggregate(Aggregate.count("n")).build(), "@query.groupBy.0", "finance"),
                place("an aggregate",
                        Query.from("Account", "a").aggregate(Aggregate.sum(BALANCE, "total")).build(),
                        "@query.aggregates.0", "finance"),
                place("a count of the values of an attribute",
                        Query.from("Account", "a").aggregate(Aggregate.count(BALANCE, "n")).build(),
                        "@query.aggregates.0", "finance"),
                place("a projection",
                        Query.from("Account", "a").select(Column.attribute("a", "balance")).build(),
                        "@query.columns.0", "finance"),
                place("the condition of a traversal",
                        Query.from("Organization", "o").traverse(Traversal.of("o", "Owns", "p").minDepth(1)
                                .relationship("owns").along(Condition.gt(SHARE, Operand.literal(50)))).build(),
                        "@query.steps.0", "ownership"),
                place("the condition of a link test",
                        Query.from("Organization", "o").where(Condition.hasLink("o", "Owns", "owns",
                                Condition.gt(SHARE, Operand.literal(50)))).build(), "@query.steps.0", "ownership"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("places")
    void aQueryThatNamesAWithheldAttributeIsRejectedAtThatPlace(String name, Query query, String path,
            String classification) {
        List<Problem> found = problems(view().restrictedTo(Clearance.none()).query(query));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).isEqualTo(path);
            assertThat(problem.message()).contains(classification);
            assertThat(problem.expected()).hasValue("a clearance that covers " + classification);
            assertThat(problem.found()).hasValue("a clearance that covers no classification");
            assertThat(problem.hint()).isPresent();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("places")
    void theSameQueryRunsThroughAViewWhoseClearanceCoversTheAttribute(String name, Query query, String path,
            String classification) {
        assertThat(view().restrictedTo(Clearance.of(classification)).query(query)).isInstanceOf(QueryResult.Rows.class);
        assertThat(view().query(query)).as("a view that is not restricted").isInstanceOf(QueryResult.Rows.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("places")
    void aRejectedQueryReadsNothing(String name, Query query, String path, String classification) {
        CountingView counting = new CountingView(view().restrictedTo(Clearance.none()));

        assertThat(counting.query(query)).isInstanceOf(QueryResult.Invalid.class);

        assertThat(counting.reads()).isZero();
    }

    @Test
    void everyPlaceIsReportedWhereAQueryNamesAWithheldAttributeMoreThanOnce() {
        Query query = Query.from("Account", "a")
                .where(Condition.gt(BALANCE, Operand.literal(money("1000"))))
                .where(Condition.eq(Operand.attribute("a", "currency"), Operand.literal("EUR")))
                .where(Condition.eq(Operand.attribute("a", "number"), Operand.literal("A-1")))
                .select(Column.attribute("a", "number"), Column.attribute("a", "balance"))
                .orderBy(Order.ascending(Column.attribute("a", "currency"))).build();

        List<Problem> found = problems(view().restrictedTo(Clearance.none()).query(query));

        assertThat(found).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.ACCESS_DENIED, "@query.steps.0"),
                tuple(ProblemCode.ACCESS_DENIED, "@query.steps.1"),
                tuple(ProblemCode.ACCESS_DENIED, "@query.columns.1"),
                tuple(ProblemCode.ACCESS_DENIED, "@query.order.0"));
        assertThat(found).extracting(problem -> problem.found().orElseThrow()).containsOnly(
                "a clearance that covers no classification");
        assertThat(found.get(1).expected()).as("another classification at another place")
                .hasValue("a clearance that covers operations");
    }

    @Test
    void aClearanceThatCoversOtherClassificationsDoesNotHelpAndIsNamedInTheProblem() {
        Query query = Query.from("Account", "a").where(Condition.gt(BALANCE, Operand.literal(money("1000")))).build();

        Problem problem = problems(view().restrictedTo(Clearance.of("identity", "ownership")).query(query)).getFirst();

        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.found()).hasValue("a clearance that covers identity, ownership");
    }

    @Test
    void aQueryThatNamesWhatIsNotWithheldIsAnsweredWithRestrictedStates() {
        Query query = Query.from("Account", "a")
                .where(Condition.eq(Operand.attribute("a", "number"), Operand.literal("A-1")))
                .select(Column.binding("a"), Column.attribute("a", "number")).build();

        QueryResult.Rows result = rows(view().restrictedTo(Clearance.none()).query(query));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.entity("a").orElseThrow().id()).isEqualTo(a1);
            assertThat(row.entity("a").orElseThrow().withheld()).containsExactly("currency", "balance");
            assertThat(row.string("a.number")).hasValue("A-1");
        });
    }

    @Test
    void aGroupedQueryOverWhatIsNotWithheldCountsTheRowsAndGroupsByTheBindingItself() {
        Query query = Query.from("Account", "a").groupBy(GroupKey.binding("a")).aggregate(Aggregate.count("n")).build();

        QueryResult.Rows result = rows(view().restrictedTo(Clearance.none()).query(query));

        assertThat(result.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.entity("a").orElseThrow().withheld()).containsExactly("currency", "balance");
            assertThat(row.longValue("n")).hasValue(1L);
        });
    }

    @Test
    void aQueryThatStartsFromGivenEntitiesIsCheckedBeforeAnythingIsRead() {
        CountingView counting = new CountingView(view().restrictedTo(Clearance.none()));
        Query query = Query.from("Account", "a").ids(a1)
                .where(Condition.gt(BALANCE, Operand.literal(money("1000")))).build();

        List<Problem> found = problems(counting.query(query));

        assertThat(found).singleElement().satisfies(problem -> assertThat(problem.code())
                .isEqualTo(ProblemCode.ACCESS_DENIED));
        assertThat(counting.reads()).isZero();
    }

    @Test
    void aTraversalWhoseConditionNamesOnlyWhatIsNotWithheldIsRun() {
        Query query = Query.from("Organization", "o").traverse(Traversal.of("o", "Owns", "p").minDepth(1)
                .relationship("owns").along(Condition.eq(Operand.type("owns"), Operand.literal("Owns")))).build();

        QueryResult.Rows result = rows(view().restrictedTo(Clearance.none()).query(query));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.relationship("owns").orElseThrow().withheld()).containsExactly("share");
            assertThat(row.entity("p").orElseThrow().withheld()).containsExactly("lei");
        });
    }
}
