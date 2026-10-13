package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.problems;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * How a view checks a query against its schema before it reads anything (design D2 of
 * {@code add-queries-and-aggregation}): every problem code, the paths and hints, names by alias and without regard to
 * case, bindings, and that every problem is reported at once without a read.
 */
class QueryCheckingTest {

    private final ReferenceStore reference = new ReferenceStore();
    private final ItemStore items = new ItemStore();

    private QueryResult run(Query query) {
        return reference.view(on(3, 31)).query(query);
    }

    private QueryResult runOnItems(Query query) {
        return items.view().query(query);
    }

    private static Operand.AttributeOf attribute(String binding, String name) {
        return Operand.attribute(binding, name);
    }

    private static void assertProblem(Problem problem, ProblemCode code, String path) {
        assertThat(problem.code()).as("code of %s", problem).isEqualTo(code);
        assertThat(problem.path()).as("path of %s", problem).isEqualTo(path);
    }

    private static Problem only(QueryResult result) {
        return QueryRows.onlyProblem(result);
    }

    private static void assertHint(Problem problem, String name) {
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'" + name + "'"));
    }

    // ------------------------------------------------------------------ names of the schema

    @Test
    void anUnknownStartTypeIsReportedAtFromWithTheClosestType() {
        Problem problem = only(run(Query.from("Organisation", "o").build()));

        assertProblem(problem, ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.from");
        assertHint(problem, "Organization");
        assertThat(problem.found()).contains("Organisation");
    }

    @Test
    void aTraitOrARelationshipTypeIsNoStartType() {
        assertProblem(only(run(Query.from("Named", "n").build())), ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.from");
        assertProblem(only(run(Query.from("Owns", "n").build())), ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.from");
    }

    @Test
    void anUnknownAttributeIsReportedAtItsStepWithTheClosestAttribute() {
        Query query = Query.from("Account", "a")
                .where(Condition.hasValue("a", "balance"))
                .where(Condition.gt(attribute("a", "balanse"), Operand.literal(1)))
                .build();

        Problem problem = only(run(query));

        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.1");
        assertHint(problem, "balance");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("balance"));
    }

    @Test
    void anAttributeThatOnlyASubtypeHasIsUnknownToABindingOfTheSupertype() {
        Problem problem = only(run(Query.from("Party", "p").where(Condition.hasValue("p", "birthDate")).build()));

        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.0");
        assertThat(problem.message()).contains("Party").contains("birthDate");
    }

    @Test
    void anUnknownRelationshipNameIsReportedForFollowTraverseAndHasLink() {
        Query query = Query.from("Organization", "o")
                .follow("o", "ownedbye", "owner")
                .traverse("o", "Ownz", 0, 3, "party")
                .where(Condition.hasLink("o", "employs"))
                .where(Condition.hasLink("o", "unrelated"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.0"),
                tuple(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.1"),
                tuple(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.3"));
        assertHint(problems.get(0), "ownedBy");
        assertHint(problems.get(1), "Owns");
        assertThat(problems.get(2).hint()).as("nothing is close to 'unrelated'").isEmpty();
    }

    @Test
    void anUnknownTypeInIsAIsReported() {
        Problem problem = only(run(Query.from("Party", "p").where(Condition.isA("p", "Persn")).build()));

        assertProblem(problem, ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.steps.0");
        assertHint(problem, "Person");
    }

    @Test
    void namesResolveByAliasAndWithoutRegardToCase() {
        Query query = Query.from("article", "i")
                .where(Condition.gt(attribute("i", "COST"), Operand.literal(1)))
                .where(Condition.hasLink("i", "SUCCESSOR"))
                .follow("i", "before", "p", "r")
                .where(Condition.isA("p", "ARTICLE"))
                .where(Condition.hasValue("r", "WEIGHT"))
                .where(Condition.hasValue("p", "Label"))
                .build();

        assertThat(runOnItems(query)).isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void inheritedAndTraitAttributesResolve() {
        // name comes from the trait Named through Party; label from the trait Labelled through Thing
        assertThat(run(Query.from("Organization", "o").where(Condition.hasValue("o", "name")).build()))
                .isInstanceOf(QueryResult.Rows.class);
        assertThat(run(Query.from("Party", "p").where(Condition.hasValue("p", "name")).build()))
                .isInstanceOf(QueryResult.Rows.class);
        assertThat(runOnItems(Query.from("Gadget", "g").where(Condition.hasValue("g", "label"))
                .where(Condition.hasValue("g", "price")).where(Condition.hasValue("g", "voltage")).build()))
                .isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void theAttributesOfABoundRelationshipResolveAgainstItsType() {
        Query valid = Query.from("Organization", "o")
                .follow("o", "ownedBy", "owner", "owns")
                .where(Condition.gt(attribute("owns", "share"), Operand.literal(50)))
                .build();
        Query invalid = Query.from("Organization", "o")
                .follow("o", "ownedBy", "owner", "owns")
                .where(Condition.gt(attribute("owns", "shares"), Operand.literal(50)))
                .build();

        assertThat(run(valid)).isInstanceOf(QueryResult.Rows.class);
        Problem problem = only(run(invalid));
        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.1");
        assertHint(problem, "share");
        assertThat(problem.message()).contains("relationship type Owns");
    }

    @Test
    void theEntityTypeAtTheOtherEndOfAFollowTypesItsBinding() {
        // ownedBy leads to the source of Owns, an Organization, which has lei; employedBy leads to a Person
        Query lei = Query.from("Party", "p").follow("p", "ownedBy", "owner")
                .where(Condition.hasValue("owner", "lei")).build();
        Query birthDate = Query.from("Organization", "o").follow("o", "Employs", "employee")
                .where(Condition.hasValue("employee", "birthDate")).build();
        Query leiOfAPerson = Query.from("Organization", "o").follow("o", "Employs", "employee")
                .where(Condition.hasValue("employee", "lei")).build();

        assertThat(run(lei)).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(birthDate)).isInstanceOf(QueryResult.Rows.class);
        assertProblem(only(run(leiOfAPerson)), ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.1");
    }

    // ------------------------------------------------------------------ the condition of a link test

    private static Query linkTest(Condition along) {
        return Query.from("Organization", "o")
                .where(Condition.hasLink("o", "ownedBy", "owns", along))
                .build();
    }

    @Test
    void theConditionOfALinkTestReadsTheAttributesOfTheRelationshipTypeByNameOrInverseName() {
        Condition large = Condition.gt(attribute("owns", "share"), Operand.literal(50));

        assertThat(run(linkTest(large))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(Query.from("Party", "p").where(Condition.hasLink("p", "ownedBy", "r", Condition.gt(
                attribute("r", "share"), Operand.literal(50)))).build())).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(Query.from("Organization", "o").where(Condition.hasLink("o", "Owns", "r", Condition.gt(
                attribute("r", "share"), Operand.literal(50)))).build())).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(Query.from("Organization", "o").where(Condition.hasLink("o", "Employs", "job", Condition.hasValue(
                "job", "title"))).build())).isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void anAttributeThatTheRelationshipTypeOfALinkTestLacksIsUnknownWithTheClosestOne() {
        Problem problem = only(run(linkTest(Condition.gt(attribute("owns", "shares"), Operand.literal(50)))));

        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.0");
        assertHint(problem, "share");
        assertThat(problem.message()).contains("relationship type Owns");
        assertProblem(only(run(Query.from("Organization", "o").where(Condition.hasLink("o", "Employs", "job",
                Condition.hasValue("job", "share"))).build())), ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.0");
    }

    @Test
    void theConditionOfALinkTestCannotReferToAnythingButItsRelationship() {
        List<Problem> problems = problems(run(linkTest(Condition.and(
                Condition.gt(attribute("o", "name"), Operand.literal("A")),
                Condition.hasValue("elsewhere", "name"),
                Condition.hasLink("o", "ownedBy")))));

        assertThat(problems).hasSize(3).allSatisfy(problem -> {
            assertProblem(problem, ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
            assertThat(problem.message()).contains("'along' condition of a hasLink test", "'owns'");
        });
    }

    @Test
    void aLinkTestThatDoesNotNameItsRelationshipHasNoBindingForItsConditionToReferTo() {
        Condition along = Condition.gt(attribute("owns", "share"), Operand.literal(50));
        Query query = Query.from("Organization", "o")
                .where(new Condition.HasLink("o", "ownedBy", Optional.empty(), Optional.of(along))).build();

        Problem problem = only(run(query));

        assertProblem(problem, ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
        assertThat(problem.message()).contains("does not name it", "hasLink test");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Name the relationship"));
    }

    @Test
    void aNameForTheRelationshipWithoutAConditionIsHarmless() {
        Query query = Query.from("Organization", "o")
                .where(new Condition.HasLink("o", "ownedBy", Optional.of("owns"), Optional.empty())).build();

        assertThat(run(query)).isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void theRelationshipOfALinkTestIsNoEntityAndValuesMustBeComparable() {
        List<Problem> problems = problems(run(linkTest(Condition.and(Condition.hasLink("owns", "ownedBy"),
                Condition.gt(attribute("owns", "share"), Operand.literal("many"))))));

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_BINDING,
                ProblemCode.INCOMPARABLE_VALUES);
        assertThat(problems).extracting(Problem::path).containsOnly("@query.steps.0");
    }

    @Test
    void anUnknownRelationshipNameOfALinkTestIsOneProblemWhateverItsConditionSays() {
        Query query = Query.from("Organization", "o").where(Condition.hasLink("o", "ownedBi", "owns",
                Condition.gt(attribute("owns", "share"), Operand.literal(50)))).build();

        Problem problem = only(run(query));

        assertProblem(problem, ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.0");
        assertHint(problem, "ownedBy");
    }

    @Test
    void aLinkTestInTheConditionOfATraversalOrOfAnotherLinkTestHasNoEntityToStartFrom() {
        Condition inner = Condition.hasLink("o", "ownedBy", "x", Condition.eq(Operand.literal(1), Operand.literal(1)));
        Query inTraversal = Query.from("Organization", "o")
                .traverse("o", "ownedBy", 1, 2, "owner", Optional.of("owns"), Optional.empty(), Optional.of(inner))
                .build();
        Query inLinkTest = linkTest(inner);

        assertProblem(only(run(inTraversal)), ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
        assertProblem(only(run(inLinkTest)), ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
    }

    @Test
    void theProblemsOfALinkTestAndOfTheRestOfTheQueryAreReportedTogetherInTheirSteps() {
        Query query = Query.from("Organization", "o")
                .where(Condition.hasLink("o", "ownedBy", "owns", Condition.gt(attribute("o", "name"),
                        Operand.literal("A"))))
                .where(Condition.hasValue("o", "leii"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.steps.0"),
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.1"));
    }

    // ------------------------------------------------------------------ bindings

    @Test
    void anUnknownBindingIsReportedWithTheClosestBinding() {
        Query query = Query.from("Account", "account")
                .where(Condition.gt(attribute("acount", "balance"), Operand.literal(1)))
                .build();

        Problem problem = only(run(query));

        assertProblem(problem, ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
        assertHint(problem, "account");
    }

    @Test
    void bindingsAreComparedExactly() {
        Problem problem = only(run(Query.from("Account", "a").where(Condition.hasValue("A", "balance")).build()));

        assertProblem(problem, ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
        assertHint(problem, "a");
    }

    @Test
    void aBindingIsNotVisibleBeforeTheStepThatBindsIt() {
        Query query = Query.from("Account", "a")
                .where(Condition.hasValue("holder", "name"))
                .follow("a", "HeldBy", "holder")
                .build();

        assertProblem(only(run(query)), ProblemCode.UNKNOWN_BINDING, "@query.steps.0");
    }

    @Test
    void everyPartThatNamesABindingReportsAnUnknownOne() {
        Query steps = Query.from("Account", "a")
                .follow("x", "HeldBy", "holder")
                .where(Condition.eq(Operand.id("y"), Operand.literal("1")))
                .where(Condition.eq(Operand.type("y"), Operand.literal("Account")))
                .where(Condition.hasLink("y", "HeldBy"))
                .where(Condition.isA("y", "Account"))
                .groupBy(GroupKey.attribute("y", "balance"), GroupKey.binding("y"))
                .aggregate(Aggregate.sum(attribute("y", "balance"), "total"))
                .build();
        Query columns = Query.from("Account", "a")
                .select(Column.binding("y", "c0"), Column.attribute("y", "balance", "c1"), Column.depth("y", "c2"))
                .orderBy(Order.ascending(Column.binding("y")))
                .build();

        List<Problem> stepProblems = problems(run(steps));
        List<Problem> columnProblems = problems(run(columns));

        assertThat(stepProblems).extracting(Problem::code).containsOnly(ProblemCode.UNKNOWN_BINDING);
        assertThat(stepProblems).extracting(Problem::path).containsExactly("@query.steps.0", "@query.steps.1",
                "@query.steps.2", "@query.steps.3", "@query.steps.4", "@query.groupBy.0", "@query.groupBy.1",
                "@query.aggregates.0");
        assertThat(columnProblems).extracting(Problem::code).containsOnly(ProblemCode.UNKNOWN_BINDING);
        assertThat(columnProblems).extracting(Problem::path).containsExactly("@query.columns.0", "@query.columns.1",
                "@query.columns.2", "@query.order.0");
    }

    @Test
    void aBindingOfTheWrongKindIsReported() {
        Query query = Query.from("Organization", "o")
                .traverse("o", "ownedBy", 0, 2, "owner", Optional.empty(), Optional.of("depth"))
                .where(Condition.hasValue("depth", "name"))
                .traverse("depth", "ownedBy", 0, 1, "again")
                .where(Condition.hasLink("o", "ownedBy"))
                .select(Column.depth("o"), Column.binding("depth"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.steps.1"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.steps.2"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.1"));
        assertThat(problems.getFirst().message()).contains("depth").contains("a depth");
    }

    @Test
    void aNameThatIsBoundTwiceIsReportedAtTheSecondStep() {
        Query query = Query.from("Account", "a")
                .follow("a", "HeldBy", "a")
                .follow("a", "HeldBy", "holder", "holder")
                .traverse("a", "HeldBy", 0, 1, "other", Optional.of("a"), Optional.of("holder"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.steps.0"),
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.steps.1"),
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.steps.2"),
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.steps.2"));
        assertThat(problems.getFirst().message()).contains("'a'");
    }

    @Test
    void twoKeysAggregatesOrColumnsWithTheSameNameAreReported() {
        Query query = Query.from("Account", "a")
                .groupBy(GroupKey.attribute("a", "currency", "x"), GroupKey.attribute("a", "number", "x"))
                .aggregate(Aggregate.count("x"), Aggregate.count("n"))
                .select(Column.key("x", "same"), Column.aggregate("n", "same"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.groupBy.1"),
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.aggregates.0"),
                tuple(ProblemCode.DUPLICATE_BINDING, "@query.columns.1"));
    }

    // ------------------------------------------------------------------ comparisons

    @Test
    void valuesOfDifferentKindsAreIncomparable() {
        Query query = Query.from("Account", "a")
                .where(Condition.gt(attribute("a", "balance"), Operand.literal("lots")))
                .where(Condition.eq(attribute("a", "number"), Operand.literal(5)))
                .where(Condition.eq(Operand.id("a"), Operand.literal(5)))
                .where(Condition.eq(Operand.literal(true), attribute("a", "currency")))
                .where(Condition.lt(attribute("a", "balance"), attribute("a", "number")))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.0"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.1"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.2"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.3"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.4"));
        assertThat(problems.getFirst().message()).contains("a.balance", "decimal", "'lots'", "string");
        assertThat(problems.getFirst().expected()).hasValueSatisfying(text -> assertThat(text).contains("number"));
    }

    @Test
    void booleansCompareForEqualityOnly() {
        Query equality = Query.from("Item", "i")
                .where(Condition.eq(attribute("i", "active"), Operand.literal(true)))
                .where(Condition.ne(attribute("i", "active"), Operand.literal(true)))
                .build();
        Query order = Query.from("Item", "i")
                .where(Condition.compare(attribute("i", "active"), Comparison.LT, Operand.literal(true)))
                .build();

        assertThat(runOnItems(equality)).isInstanceOf(QueryResult.Rows.class);
        Problem problem = only(runOnItems(order));
        assertProblem(problem, ProblemCode.INCOMPARABLE_VALUES, "@query.steps.0");
        assertThat(problem.message()).contains("Booleans compare for equality only");
    }

    @Test
    void anInListIsCheckedValueByValue() {
        Query query = Query.from("Account", "a")
                .where(Condition.in(attribute("a", "balance"), Value.of(1), Value.of("two"), Value.of(3L),
                        Value.of(true)))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.0"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.0"));
        assertThat(problems).extracting(Problem::message).satisfiesExactly(
                first -> assertThat(first).contains("'two'"), second -> assertThat(second).contains("true"));
    }

    @Test
    void stringsThatSpellDatesAndInstantsFitDateAndInstantAttributes() {
        Query valid = Query.from("Item", "i")
                .where(Condition.ge(attribute("i", "born"), Operand.literal("2020-01-01")))
                .where(Condition.ge(attribute("i", "seen"), Operand.literal("2026-02-01T10:00:00+01:00")))
                .build();
        Query invalid = Query.from("Item", "i")
                .where(Condition.ge(attribute("i", "born"), Operand.literal("yesterday")))
                .where(Condition.ge(attribute("i", "seen"), Operand.literal("2026-02-01")))
                .where(Condition.ge(attribute("i", "label"), Operand.literal(java.time.LocalDate.of(2020, 1, 1))))
                .build();

        assertThat(runOnItems(valid)).isInstanceOf(QueryResult.Rows.class);
        assertThat(problems(runOnItems(invalid))).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.0"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.1"),
                tuple(ProblemCode.INCOMPARABLE_VALUES, "@query.steps.2"));
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    void anAggregateThatCannotBeComputedIsReported() {
        Query query = Query.from("Item", "i")
                .aggregate(
                        Aggregate.sum(attribute("i", "label"), "sumOfLabels"),
                        Aggregate.avg(attribute("i", "born"), "averageBirth"),
                        Aggregate.min(attribute("i", "active"), "minimumFlag"),
                        Aggregate.max(attribute("i", "active"), "maximumFlag"),
                        new Aggregate(Aggregate.Function.SUM, Optional.empty(), "sumOfNothing"),
                        new Aggregate(Aggregate.Function.MIN, Optional.empty(), "minimumOfNothing"))
                .build();

        List<Problem> problems = problems(runOnItems(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.0"),
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.1"),
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.2"),
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.3"),
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.4"),
                tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.5"));
        assertThat(problems).extracting(Problem::message).satisfiesExactly(
                sum -> assertThat(sum).contains("sum needs numbers").contains("label").contains("string"),
                average -> assertThat(average).contains("average needs numbers").contains("date"),
                minimum -> assertThat(minimum).contains("minimum needs values that can be ordered").contains("boolean"),
                maximum -> assertThat(maximum).contains("maximum needs values that can be ordered"),
                none -> assertThat(none).contains("sum needs an attribute"),
                other -> assertThat(other).contains("minimum needs an attribute"));
    }

    @Test
    void everyFunctionWorksOnTheAttributesItSuits() {
        Query query = Query.from("Item", "i")
                .aggregate(
                        Aggregate.count("rows"),
                        Aggregate.count(attribute("i", "label"), "labels"),
                        Aggregate.count(attribute("i", "active"), "flags"),
                        Aggregate.sum(attribute("i", "quantity"), "quantities"),
                        Aggregate.sum(attribute("i", "serial"), "serials"),
                        Aggregate.sum(attribute("i", "price"), "prices"),
                        Aggregate.avg(attribute("i", "quantity"), "averageQuantity"),
                        Aggregate.min(attribute("i", "label"), "firstLabel"),
                        Aggregate.max(attribute("i", "born"), "lastBirth"),
                        Aggregate.max(attribute("i", "seen"), "lastSeen"))
                .build();

        assertThat(rows(runOnItems(query)).rows()).hasSize(1);
    }

    // ------------------------------------------------------------------ columns, order and grouping

    @Test
    void afterGroupingOnlyKeysAndAggregatesAreAvailable() {
        Query query = Query.from("Account", "a")
                .groupBy(GroupKey.attribute("a", "currency", "currency"))
                .aggregate(Aggregate.count("n"))
                .select(Column.binding("a", "c0"), Column.attribute("a", "balance", "c1"), Column.key("currency", "c2"),
                        Column.key("n", "c3"), Column.aggregate("currency", "c4"), Column.key("curency", "c5"),
                        Column.aggregate("m", "c6"))
                .orderBy(Order.ascending(Column.depth("a")), Order.descending(Column.aggregate("m")))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.1"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.3"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.4"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.5"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.6"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.order.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.order.1"));
        assertThat(problems.get(2).message()).as("n is an aggregate").contains("'n' is an aggregate");
        assertThat(problems.get(3).message()).as("currency is a key").contains("'currency' is a group key");
        assertHint(problems.get(4), "currency");
        assertHint(problems.get(5), "n");
    }

    @Test
    void withoutGroupingThereAreNoKeysOrAggregates() {
        Query query = Query.from("Account", "a")
                .select(Column.key("a"), Column.aggregate("n"))
                .orderBy(Order.ascending(Column.key("a")))
                .build();

        assertThat(problems(run(query))).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.1"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.order.0"));
    }

    @Test
    void anUnknownAttributeInAColumnAnOrderOrAKeyIsReportedAtItsPart() {
        Query grouped = Query.from("Account", "a")
                .groupBy(GroupKey.attribute("a", "currensy"))
                .build();
        Query ungrouped = Query.from("Account", "a")
                .select(Column.attribute("a", "balanse"))
                .orderBy(Order.ascending(Column.attribute("a", "numbr")))
                .build();

        Problem key = only(run(grouped));
        List<Problem> others = problems(run(ungrouped));

        assertProblem(key, ProblemCode.UNKNOWN_ATTRIBUTE, "@query.groupBy.0");
        assertHint(key, "currency");
        assertThat(others).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.columns.0"),
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.order.0"));
        assertHint(others.get(0), "balance");
        assertHint(others.get(1), "number");
    }

    // ------------------------------------------------------------------ all at once, and nothing read

    @Test
    void everyProblemOfAQueryIsReportedAtOnceInTheOrderOfItsParts() {
        Query query = Query.from("Acount", "a")
                .where(Condition.hasValue("a", "balance"))
                .follow("a", "HeldBye", "holder")
                .where(Condition.gt(attribute("holder", "name"), Operand.literal(1)))
                .groupBy(GroupKey.binding("zzz"))
                .aggregate(Aggregate.sum(attribute("holder", "name"), "total"))
                .select(Column.binding("holder", "holder"), Column.binding("nobody"))
                .orderBy(Order.ascending(Column.binding("none")))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.from"),
                tuple(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.1"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.groupBy.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.0"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.1"),
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.order.0"));
    }

    @Test
    void oneMistakeIsOneProblem() {
        // The unknown relationship name leaves the binding holder without a type; what is asked of it is not reported
        Query query = Query.from("Account", "a")
                .follow("a", "HeldBye", "holder")
                .where(Condition.hasValue("holder", "whatever"))
                .where(Condition.gt(attribute("holder", "whatever"), Operand.literal(1)))
                .groupBy(GroupKey.attribute("holder", "whatever"))
                .build();

        Problem problem = only(run(query));

        assertProblem(problem, ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.0");
    }

    @Test
    void aQueryThatDoesNotFitReadsNothingAndDoesNotThrow() {
        reference.createAccounts();
        CountingView view = new CountingView(reference.view(on(3, 31)));
        Query query = Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .where(Condition.gt(attribute("a", "balanse"), Operand.literal(1)))
                .build();

        QueryResult result = view.query(query);

        assertThat(result).isInstanceOf(QueryResult.Invalid.class);
        assertThat(view.reads()).isZero();
    }

    @Test
    void aValidQueryReadsThroughTheView() {
        reference.createAccounts();
        CountingView view = new CountingView(reference.view(on(3, 31)));

        QueryResult result = view.query(Query.from("Account", "a").follow("a", "HeldBy", "holder").build());

        assertThat(rows(result).rows()).hasSize(2);
        assertThat(view.reads()).isPositive();
    }

    @Test
    void theProblemsAreTheSameWhateverViewRunsTheQuery() {
        View plain = reference.view(on(3, 31));
        Query query = Query.from("Acount", "a").build();

        assertThat(new CountingView(plain).query(query)).isEqualTo(plain.query(query));
    }
}
