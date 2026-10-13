package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.problem;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.problems;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryStep;
import io.github.enricoag1982.cairn.query.Start;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Reading queries (design D4 of {@code add-queries-and-aggregation}): the keys of every level and what they build, YAML
 * as well as JSON, literals, defaults, and the located problems of text that is not a query.
 */
class QueryReadingTest {

    private static String from(String rest) {
        return "{ \"from\": { \"type\": \"Account\", \"as\": \"a\" }" + (rest.isEmpty() ? "" : ", " + rest) + " }";
    }

    private static String step(String step) {
        return from("\"steps\": [ " + step + " ]");
    }

    private static QueryStep onlyStep(String text) {
        List<QueryStep> steps = query(text).steps();
        assertThat(steps).hasSize(1);
        return steps.getFirst();
    }

    private static Condition condition(String condition) {
        return ((QueryStep.Filter) onlyStep(step("{ \"where\": " + condition + " }"))).condition();
    }

    private static Operand operand(String operand) {
        return ((Condition.Compare) condition("{ \"compare\": { \"left\": " + operand
                + ", \"op\": \"eq\", \"right\": { \"value\": 1 } } }")).left();
    }

    // ------------------------------------------------------------------ what is read

    @Test
    void aQueryWithOnlyAStartIsTheStartOfAnEntityType() {
        Query query = query(from(""));

        assertThat(query).isEqualTo(Query.from("Account", "a").build());
        assertThat(query.grouping()).isEmpty();
        assertThat(query.columns()).isEmpty();
        assertThat(query.limit()).isEmpty();
    }

    @Test
    void theStartCanAskForTheExactType() {
        assertThat(query("{ \"from\": { \"type\": \"Party\", \"as\": \"p\", \"exact\": true } }").start())
                .isEqualTo(new Start("Party", "p", true));
        assertThat(query("{ \"from\": { \"type\": \"Party\", \"as\": \"p\", \"exact\": false } }").start())
                .isEqualTo(new Start("Party", "p", false));
    }

    @Test
    void everyKindOfStepIsRead() {
        Query query = query("""
                {
                  "from": { "type": "Organization", "as": "o" },
                  "steps": [
                    { "where": { "hasValue": { "attribute": "lei", "of": "o" } } },
                    { "follow": { "from": "o", "name": "Owns", "as": "owned" } },
                    { "follow": { "from": "o", "name": "Owns", "as": "other", "relationship": "owns" } },
                    { "optionalFollow": { "from": "o", "name": "Employs", "as": "employee" } },
                    { "optionalFollow": { "from": "o", "name": "Employs", "as": "worker", "relationship": "job" } },
                    { "traverse": { "from": "o", "name": "ownedBy", "as": "party", "maxDepth": 3 } },
                    { "traverse": { "from": "o", "name": "ownedBy", "as": "owner", "minDepth": 1, "maxDepth": 2,
                                    "relationship": "via", "depth": "depth" } }
                  ]
                }
                """);

        assertThat(query.steps()).containsExactly(
                new QueryStep.Filter(Condition.hasValue("o", "lei")),
                new QueryStep.Follow("o", "Owns", "owned", Optional.empty(), false),
                new QueryStep.Follow("o", "Owns", "other", Optional.of("owns"), false),
                new QueryStep.Follow("o", "Employs", "employee", Optional.empty(), true),
                new QueryStep.Follow("o", "Employs", "worker", Optional.of("job"), true),
                new QueryStep.Traverse("o", "ownedBy", "party", Optional.empty(), Optional.empty(), 0, 3),
                new QueryStep.Traverse("o", "ownedBy", "owner", Optional.of("via"), Optional.of("depth"), 1, 2));
    }

    @Test
    void everyKindOfConditionIsRead() {
        Operand balance = Operand.attribute("a", "balance");

        assertThat(condition("{ \"compare\": { \"left\": { \"attribute\": \"balance\", \"of\": \"a\" }, "
                + "\"op\": \"gt\", \"right\": { \"value\": 1200 } } }"))
                .isEqualTo(Condition.gt(balance, Operand.literal(1200)));
        assertThat(condition("{ \"in\": { \"operand\": { \"attribute\": \"number\", \"of\": \"a\" }, "
                + "\"values\": [\"A-1\", \"A-2\"] } }"))
                .isEqualTo(Condition.in(Operand.attribute("a", "number"), Value.of("A-1"), Value.of("A-2")));
        assertThat(condition("{ \"hasValue\": { \"attribute\": \"balance\", \"of\": \"a\" } }"))
                .isEqualTo(Condition.hasValue("a", "balance"));
        assertThat(condition("{ \"hasLink\": { \"of\": \"a\", \"name\": \"HeldBy\" } }"))
                .isEqualTo(Condition.hasLink("a", "HeldBy"));
        assertThat(condition("{ \"isA\": { \"of\": \"a\", \"type\": \"Account\" } }"))
                .isEqualTo(Condition.isA("a", "Account"));
        assertThat(condition("{ \"and\": [ { \"hasLink\": { \"of\": \"a\", \"name\": \"HeldBy\" } }, "
                + "{ \"isA\": { \"of\": \"a\", \"type\": \"Account\" } } ] }"))
                .isEqualTo(Condition.and(Condition.hasLink("a", "HeldBy"), Condition.isA("a", "Account")));
        assertThat(condition("{ \"or\": [ { \"hasLink\": { \"of\": \"a\", \"name\": \"HeldBy\" } } ] }"))
                .isEqualTo(Condition.or(Condition.hasLink("a", "HeldBy")));
        assertThat(condition("{ \"not\": { \"hasValue\": { \"attribute\": \"balance\", \"of\": \"a\" } } }"))
                .isEqualTo(Condition.not(Condition.hasValue("a", "balance")));
        assertThat(condition("{ \"and\": [] }")).isEqualTo(Condition.and());
        assertThat(condition("{ \"or\": [] }")).isEqualTo(Condition.or());
    }

    @Test
    void everyComparisonIsRead() {
        for (Comparison comparison : Comparison.values()) {
            String op = comparison.name().toLowerCase();

            assertThat(condition("{ \"compare\": { \"left\": { \"id\": \"a\" }, \"op\": \"" + op
                    + "\", \"right\": { \"value\": \"x\" } } }"))
                    .isEqualTo(Condition.compare(Operand.id("a"), comparison, Operand.literal("x")));
        }
    }

    @Test
    void everyKindOfOperandIsRead() {
        assertThat(operand("{ \"attribute\": \"balance\", \"of\": \"a\" }")).isEqualTo(Operand.attribute("a", "balance"));
        assertThat(operand("{ \"id\": \"a\" }")).isEqualTo(Operand.id("a"));
        assertThat(operand("{ \"type\": \"a\" }")).isEqualTo(Operand.type("a"));
        assertThat(operand("{ \"value\": \"x\" }")).isEqualTo(Operand.literal("x"));
    }

    @Test
    void literalsAreReadAsPayloadsReadValuesOfAnUnknownType() {
        assertThat(operand("{ \"value\": 5 }")).isEqualTo(Operand.literal(Value.of(5)));
        assertThat(operand("{ \"value\": 5000000000 }")).isEqualTo(Operand.literal(Value.of(5_000_000_000L)));
        assertThat(operand("{ \"value\": 1200.50 }")).isEqualTo(Operand.literal(Value.of(new BigDecimal("1200.50"))));
        assertThat(operand("{ \"value\": 1e3 }")).isEqualTo(Operand.literal(Value.of(new BigDecimal("1e3"))));
        assertThat(operand("{ \"value\": -3 }")).isEqualTo(Operand.literal(Value.of(-3)));
        assertThat(operand("{ \"value\": true }")).isEqualTo(Operand.literal(Value.of(true)));
        assertThat(operand("{ \"value\": \"1990-05-17\" }")).as("a string stays a string")
                .isEqualTo(Operand.literal(Value.of("1990-05-17")));
        assertThat(operand("{ \"value\": \"2026-03-31T00:00:00Z\" }")).isEqualTo(Operand.literal("2026-03-31T00:00:00Z"));
        assertThat(operand("{ \"value\": \"1.10\" }")).isEqualTo(Operand.literal("1.10"));
        assertThat(operand("{ \"value\": \"123\" }")).as("a quoted number is a string").isEqualTo(Operand.literal("123"));
    }

    @Test
    void groupingColumnsOrderAndLimitAreRead() {
        Query query = query("""
                {
                  "from": { "type": "Account", "as": "a" },
                  "steps": [ { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } } ],
                  "groupBy": [
                    { "of": "holder" },
                    { "of": "holder", "as": "who" },
                    { "of": "a", "attribute": "currency" },
                    { "of": "a", "attribute": "currency", "as": "code" }
                  ],
                  "aggregates": [
                    { "function": "count", "as": "rows" },
                    { "function": "count", "attribute": "balance", "of": "a", "as": "balances" },
                    { "function": "sum", "attribute": "balance", "of": "a", "as": "total" },
                    { "function": "min", "attribute": "balance", "of": "a", "as": "least" },
                    { "function": "max", "attribute": "balance", "of": "a", "as": "most" },
                    { "function": "avg", "attribute": "balance", "of": "a", "as": "average" }
                  ],
                  "columns": [
                    { "key": "who" },
                    { "key": "code", "as": "currency" },
                    { "aggregate": "total" },
                    { "aggregate": "rows", "as": "n" }
                  ],
                  "order": [
                    { "aggregate": "total", "descending": true },
                    { "key": "who" },
                    { "key": "code", "descending": false }
                  ],
                  "limit": 5
                }
                """);

        assertThat(query.grouping().orElseThrow().keys()).containsExactly(
                GroupKey.binding("holder"), GroupKey.binding("holder", "who"), GroupKey.attribute("a", "currency"),
                GroupKey.attribute("a", "currency", "code"));
        Operand.AttributeOf balance = Operand.attribute("a", "balance");
        assertThat(query.grouping().orElseThrow().aggregates()).containsExactly(
                Aggregate.count("rows"), Aggregate.count(balance, "balances"), Aggregate.sum(balance, "total"),
                Aggregate.min(balance, "least"), Aggregate.max(balance, "most"), Aggregate.avg(balance, "average"));
        assertThat(query.columns()).containsExactly(Column.key("who"), Column.key("code", "currency"),
                Column.aggregate("total"), Column.aggregate("rows", "n"));
        assertThat(query.order()).containsExactly(Order.descending(Column.aggregate("total")),
                Order.ascending(Column.key("who")), Order.ascending(Column.key("code")));
        assertThat(query.limit()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void everyKindOfColumnAndOrderIsRead() {
        Query query = query("""
                {
                  "from": { "type": "Account", "as": "a" },
                  "steps": [ { "traverse": { "from": "a", "name": "HeldBy", "as": "h", "maxDepth": 1, "depth": "d" } } ],
                  "columns": [
                    { "binding": "a" }, { "binding": "h", "as": "holder" }, { "attribute": "balance", "of": "a" },
                    { "attribute": "balance", "of": "a", "as": "money" }, { "depth": "d" }
                  ],
                  "order": [
                    { "binding": "a" }, { "attribute": "balance", "of": "a" }, { "depth": "d", "descending": true }
                  ]
                }
                """);

        assertThat(query.columns()).containsExactly(Column.binding("a"), Column.binding("h", "holder"),
                Column.attribute("a", "balance"), Column.attribute("a", "balance", "money"), Column.depth("d"));
        assertThat(query.order()).containsExactly(Order.ascending(Column.binding("a")),
                Order.ascending(Column.attribute("a", "balance")), Order.descending(Column.depth("d")));
    }

    @Test
    void aGroupingIsThereIfEitherKeyIs() {
        assertThat(query(from("\"aggregates\": [ { \"function\": \"count\", \"as\": \"n\" } ]")).grouping())
                .hasValueSatisfying(grouping -> assertThat(grouping.keys()).isEmpty());
        assertThat(query(from("\"groupBy\": [ { \"of\": \"a\" } ]")).grouping())
                .hasValueSatisfying(grouping -> assertThat(grouping.aggregates()).isEmpty());
        assertThat(query(from("\"groupBy\": []")).grouping()).isPresent();
        assertThat(query(from("\"steps\": [], \"columns\": [], \"order\": []")).grouping()).isEmpty();
    }

    @Test
    void theKeysCairnAndSchemaAreAccepted() {
        Query query = query("{ \"$schema\": \"urn:cairn:query:1\", \"cairn\": 1, \"from\": { \"type\": \"A\", \"as\": \"a\" } }");

        assertThat(query).isEqualTo(Query.from("A", "a").build());
    }

    @Test
    void aQueryIsReadFromYamlToo() {
        String yaml = """
                from:
                  type: Account
                  as: a
                steps:
                  - follow: {from: a, name: HeldBy, as: holder}
                  - traverse:
                      from: holder
                      name: ownedBy
                      as: party
                      maxDepth: 10
                  - where:
                      not:
                        hasLink: {of: party, name: ownedBy}
                groupBy:
                  - of: party
                aggregates:
                  - {function: sum, attribute: balance, of: a, as: total}
                """;

        assertThat(query(yaml)).isEqualTo(query(QueryFixtures.TOTAL_BALANCE));
    }

    @Test
    void theLocationsOfTheTextAreKept() {
        LocatedQuery read = QueryFixtures.read(QueryFixtures.TOTAL_BALANCE);

        assertThat(read.locations().root().pointer()).isEmpty();
        assertThat(read.locations().find("@query.from")).contains(locationOf(QueryFixtures.TOTAL_BALANCE, "\"from\"", "/from"));
        assertThat(read.locations().find("@query.steps.1")).contains(locationOf(QueryFixtures.TOTAL_BALANCE,
                "{ \"traverse\"", "/steps/1"));
        assertThat(read.locations().find("@query.groupBy.0")).isPresent();
        assertThat(read.locations().find("@query.aggregates.0")).isPresent();
        assertThat(read.locations().find("@query.steps.3")).isEmpty();
    }

    // ------------------------------------------------------------------ unknown keys

    @Test
    void anUnknownKeyIsLocatedAtTheKeyWithTheClosestKnownKeyAsAHint() {
        String text = from("\"limt\": 1");

        Problem problem = problem(text);

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
        assertThat(problem.path()).isEqualTo("@query");
        assertThat(problem.location()).contains(locationOf(text, "\"limt\"", "/limt"));
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'limit'"));
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected)
                .contains("from", "steps", "groupBy", "aggregates", "columns", "order", "limit"));
    }

    @Test
    void anUnknownKeyIsReportedAtEveryLevelWithItsPath() {
        List<String[]> cases = List.of(
                new String[] {"{ \"from\": { \"type\": \"A\", \"as\": \"a\", \"exct\": true } }", "@query.from", "exact"},
                new String[] {step("{ \"folow\": { \"from\": \"a\", \"name\": \"HeldBy\", \"as\": \"h\" } }"),
                        "@query.steps.0", "follow"},
                new String[] {step("{ \"follow\": { \"form\": \"a\", \"name\": \"HeldBy\", \"as\": \"h\" } }"),
                        "@query.steps.0", "from"},
                new String[] {step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\", \"maxDepth\": 1, "
                        + "\"maxDept\": 2 } }"), "@query.steps.0", "maxDepth"},
                new String[] {step("{ \"where\": { \"hasValu\": { \"attribute\": \"x\", \"of\": \"a\" } } }"),
                        "@query.steps.0", "hasValue"},
                new String[] {step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\" }, \"opp\": \"eq\", "
                        + "\"right\": { \"id\": \"a\" } } } }"), "@query.steps.0", "op"},
                new String[] {step("{ \"where\": { \"hasLink\": { \"of\": \"a\", \"nme\": \"HeldBy\" } } }"),
                        "@query.steps.0", "name"},
                new String[] {from("\"groupBy\": [ { \"of\": \"a\", \"attribut\": \"x\" } ]"), "@query.groupBy.0",
                        "attribute"},
                new String[] {from("\"aggregates\": [ { \"function\": \"count\", \"as\": \"n\", \"ass\": \"x\" } ]"),
                        "@query.aggregates.0", "as"},
                new String[] {from("\"columns\": [ { \"binding\": \"a\", \"as\": \"x\", \"descending\": true } ]"),
                        "@query.columns.0", null},
                new String[] {from("\"order\": [ { \"binding\": \"a\", \"as\": \"x\" } ]"), "@query.order.0", null});

        for (String[] testCase : cases) {
            Problem problem = problems(testCase[0]).stream().filter(found -> found.code() == ProblemCode.UNKNOWN_KEY)
                    .findFirst().orElseThrow(() -> new AssertionError("No UNKNOWN_KEY problem in " + testCase[0]));

            assertThat(problem.code()).as(testCase[1]).isEqualTo(ProblemCode.UNKNOWN_KEY);
            assertThat(problem.path()).isEqualTo(testCase[1]);
            if (testCase[2] != null) {
                assertThat(problem.hint()).as(testCase[1]).hasValueSatisfying(hint ->
                        assertThat(hint).contains("'" + testCase[2] + "'"));
            }
        }
    }

    // ------------------------------------------------------------------ missing keys

    @Test
    void aMissingRequiredKeyIsReportedWhereItIsMissing() {
        List<String[]> cases = List.of(
                new String[] {"{}", "@query", "from"},
                new String[] {"{ \"from\": { \"as\": \"a\" } }", "@query.from", "type"},
                new String[] {"{ \"from\": { \"type\": \"A\" } }", "@query.from", "as"},
                new String[] {step("{ \"follow\": { \"name\": \"n\", \"as\": \"h\" } }"), "@query.steps.0", "from"},
                new String[] {step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\" } }"),
                        "@query.steps.0", "maxDepth"},
                new String[] {step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\" }, "
                        + "\"right\": { \"id\": \"a\" } } } }"), "@query.steps.0", "op"},
                new String[] {step("{ \"where\": { \"in\": { \"operand\": { \"id\": \"a\" } } } }"), "@query.steps.0",
                        "values"},
                new String[] {step("{ \"where\": { \"isA\": { \"of\": \"a\" } } }"), "@query.steps.0", "type"},
                new String[] {from("\"groupBy\": [ { \"attribute\": \"x\" } ]"), "@query.groupBy.0", "of"},
                new String[] {from("\"aggregates\": [ { \"function\": \"count\" } ]"), "@query.aggregates.0", "as"},
                new String[] {from("\"aggregates\": [ { \"as\": \"n\" } ]"), "@query.aggregates.0", "function"});

        for (String[] testCase : cases) {
            Problem problem = problems(testCase[0]).getFirst();

            assertThat(problem.code()).as(testCase[1] + " " + testCase[2]).isEqualTo(ProblemCode.MISSING_KEY);
            assertThat(problem.path()).isEqualTo(testCase[1]);
            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains(testCase[2]));
        }
    }

    @Test
    void aMappingThatShouldNameItsKindButDoesNotIsReported() {
        for (String empty : List.of(step("{}"), step("{ \"where\": {} }"),
                step("{ \"where\": { \"compare\": { \"left\": {}, \"op\": \"eq\", \"right\": { \"id\": \"a\" } } } }"),
                from("\"columns\": [ {} ]"), from("\"order\": [ {} ]"))) {
            Problem problem = problem(empty);

            assertThat(problem.code()).as(empty).isEqualTo(ProblemCode.MISSING_KEY);
            assertThat(problem.message()).contains("naming what it is");
        }
        assertThat(problem(from("\"columns\": [ { \"of\": \"a\" } ]")).code()).isEqualTo(ProblemCode.MISSING_KEY);
    }

    @Test
    void anAttributeNeedsItsBindingAndNothingElseGoesWithABinding() {
        Problem noBinding = problem(from("\"columns\": [ { \"attribute\": \"balance\" } ]"));
        Problem aloneBinding = problem(from("\"columns\": [ { \"binding\": \"a\", \"of\": \"a\" } ]"));
        Problem noAttribute = problem(from("\"aggregates\": [ { \"function\": \"sum\", \"of\": \"a\", \"as\": \"t\" } ]"));
        Problem noOperandBinding = problem(step("{ \"where\": { \"compare\": { \"left\": { \"attribute\": \"x\" }, "
                + "\"op\": \"eq\", \"right\": { \"id\": \"a\" } } } }"));

        assertThat(noBinding.code()).isEqualTo(ProblemCode.MISSING_KEY);
        assertThat(noBinding.expected()).contains("the key 'of'");
        assertThat(aloneBinding.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(aloneBinding.message()).contains("'of' goes with 'attribute' only");
        assertThat(noAttribute.code()).isEqualTo(ProblemCode.MISSING_KEY);
        assertThat(noAttribute.path()).isEqualTo("@query.aggregates.0");
        assertThat(noOperandBinding.code()).isEqualTo(ProblemCode.MISSING_KEY);
    }

    // ------------------------------------------------------------------ values of the wrong kind or form

    @Test
    void aStepOrConditionWithSeveralKindsIsReported() {
        Problem step = problem(step("{ \"where\": { \"isA\": { \"of\": \"a\", \"type\": \"T\" } }, "
                + "\"follow\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\" } }"));
        Problem condition = problem(step("{ \"where\": { \"isA\": { \"of\": \"a\", \"type\": \"T\" }, "
                + "\"not\": { \"isA\": { \"of\": \"a\", \"type\": \"T\" } } } }"));
        Problem operand = problem(step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\", \"type\": \"a\" }, "
                + "\"op\": \"eq\", \"right\": { \"id\": \"a\" } } } }"));

        for (Problem problem : List.of(step, condition, operand)) {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.path()).isEqualTo("@query.steps.0");
            assertThat(problem.message()).contains("one key naming what it is");
        }
    }

    @Test
    void aValueOfTheWrongKindIsReportedWhereItIs() {
        List<String[]> cases = List.of(
                new String[] {"[]", "@query"},
                new String[] {"{ \"from\": \"Account\" }", "@query.from"},
                new String[] {"{ \"from\": { \"type\": 1, \"as\": \"a\" } }", "@query.from"},
                new String[] {"{ \"from\": { \"type\": \"A\", \"as\": \"a\", \"exact\": \"yes\" } }", "@query.from"},
                new String[] {from("\"steps\": { }"), "@query.steps"},
                new String[] {step("5"), "@query.steps.0"},
                new String[] {step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\", "
                        + "\"maxDepth\": \"2\" } }"), "@query.steps.0"},
                new String[] {step("{ \"where\": { \"and\": { } } }"), "@query.steps.0"},
                new String[] {step("{ \"where\": { \"in\": { \"operand\": { \"id\": \"a\" }, \"values\": 5 } } }"),
                        "@query.steps.0"},
                new String[] {step("{ \"where\": { \"in\": { \"operand\": { \"id\": \"a\" }, \"values\": [ null ] } } }"),
                        "@query.steps.0"},
                new String[] {step("{ \"where\": { \"in\": { \"operand\": { \"id\": \"a\" }, \"values\": [ [] ] } } }"),
                        "@query.steps.0"},
                new String[] {step("{ \"where\": { \"compare\": { \"left\": { \"value\": [] }, \"op\": \"eq\", "
                        + "\"right\": { \"id\": \"a\" } } } }"), "@query.steps.0"},
                new String[] {step("{ \"where\": { \"compare\": { \"left\": { \"id\": 5 }, \"op\": \"eq\", "
                        + "\"right\": { \"id\": \"a\" } } } }"), "@query.steps.0"},
                new String[] {from("\"groupBy\": { }"), "@query.groupBy"},
                new String[] {from("\"groupBy\": [ \"a\" ]"), "@query.groupBy.0"},
                new String[] {from("\"aggregates\": [ { \"function\": \"count\", \"as\": 5 } ]"), "@query.aggregates.0"},
                new String[] {from("\"columns\": [ { \"binding\": true } ]"), "@query.columns.0"},
                new String[] {from("\"order\": [ { \"binding\": \"a\", \"descending\": \"yes\" } ]"), "@query.order.0"},
                new String[] {from("\"limit\": \"5\""), "@query.limit"},
                new String[] {from("\"limit\": 1.5"), "@query.limit"},
                new String[] {from("\"limit\": null"), "@query.limit"},
                new String[] {from("\"cairn\": \"one\""), "@query"},
                new String[] {from("\"$schema\": 5"), "@query"});

        for (String[] testCase : cases) {
            Problem problem = problems(testCase[0]).getFirst();

            assertThat(problem.code()).as(testCase[0]).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.path()).as(testCase[0]).isEqualTo(testCase[1]);
        }
    }

    @Test
    void aValueThatTheRecordsRejectIsAProblemOfReadingAndNoException() {
        Problem negativeLimit = problem(from("\"limit\": -1"));
        Problem negativeMinimum = problem(step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\", "
                + "\"minDepth\": -1, \"maxDepth\": 2 } }"));
        Problem negativeMaximum = problem(step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\", "
                + "\"maxDepth\": -2 } }"));
        Problem maximumBelowMinimum = problem(step("{ \"traverse\": { \"from\": \"a\", \"name\": \"n\", \"as\": \"h\", "
                + "\"minDepth\": 3, \"maxDepth\": 2 } }"));

        assertThat(negativeLimit.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(negativeLimit.path()).isEqualTo("@query.limit");
        assertThat(negativeLimit.expected()).contains("a whole number of at least 0");
        assertThat(negativeMinimum.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(negativeMinimum.message()).contains("minimum depth");
        assertThat(negativeMaximum.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(maximumBelowMinimum.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(maximumBelowMinimum.expected()).contains("a whole number of at least 3");
        assertThat(maximumBelowMinimum.location()).contains(locationOf(step("{ \"traverse\": { \"from\": \"a\", "
                + "\"name\": \"n\", \"as\": \"h\", \"minDepth\": 3, \"maxDepth\": 2 } }"), "2 }", "/steps/0/traverse/maxDepth"));
    }

    @Test
    void anOperatorAndAFunctionAreChoicesWithAHintForTheClosest() {
        Problem operator = problem(step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\" }, \"op\": \"gte\", "
                + "\"right\": { \"id\": \"a\" } } } }"));
        Problem function = problem(from("\"aggregates\": [ { \"function\": \"total\", \"as\": \"t\" } ]"));
        Problem symbol = problem(step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\" }, \"op\": \">\", "
                + "\"right\": { \"id\": \"a\" } } } }"));

        assertThat(operator.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(operator.path()).isEqualTo("@query.steps.0");
        assertThat(operator.expected()).contains("one of eq, ne, lt, le, gt, ge");
        assertThat(function.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(function.path()).isEqualTo("@query.aggregates.0");
        assertThat(function.expected()).contains("one of count, sum, min, max, avg");
        assertThat(operator.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'ge'"));
        assertThat(symbol.hint()).isPresent();
    }

    @Test
    void aNumberThatOnlyYamlKnowsIsAProblemWithAHint() {
        Problem hex = problem("{ \"from\": { \"type\": \"A\", \"as\": \"a\" }, \"limit\": 0x10 }");
        Problem literal = problem(step("{ \"where\": { \"compare\": { \"left\": { \"id\": \"a\" }, \"op\": \"eq\", "
                + "\"right\": { \"value\": .5 } } } }"));

        assertThat(hex.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(hex.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("JSON number"));
        assertThat(literal.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(literal.path()).isEqualTo("@query.steps.0");
    }

    @Test
    void everyProblemIsReportedInTheOrderOfTheText() {
        String text = """
                {
                  "from": { "type": "A" },
                  "steps": [
                    { "folow": { "from": "a", "name": "n", "as": "h" } },
                    { "where": { "isA": { "of": "a" } } }
                  ],
                  "limit": -1
                }
                """;

        List<Problem> problems = problems(text);

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.MISSING_KEY, "@query.from"),
                tuple(ProblemCode.UNKNOWN_KEY, "@query.steps.0"),
                tuple(ProblemCode.MISSING_KEY, "@query.steps.1"),
                tuple(ProblemCode.INVALID_VALUE, "@query.limit"));
        assertThat(problems).extracting(problem -> problem.location().orElseThrow().line()).isSorted();
    }

    // ------------------------------------------------------------------ the text as a whole

    @Test
    void textThatIsNotYamlIsASyntaxProblemAndTheOnlyOne() {
        Problem problem = problem("{ \"from\": ");

        assertThat(problem.code()).isEqualTo(ProblemCode.DOCUMENT_SYNTAX);
        assertThat(problem.path()).isEqualTo("@query");
        assertThat(problem.location().orElseThrow().line()).isPositive();
    }

    @Test
    void anEmptyTextIsAMissingFrom() {
        for (String text : List.of("", "  \n", "# nothing")) {
            Problem problem = problems(text).getFirst();

            assertThat(problem.code()).as(text).isEqualTo(ProblemCode.MISSING_KEY);
            assertThat(problem.path()).isEqualTo("@query");
        }
        assertThat(problem("---\n").code()).as("a document that is null").isEqualTo(ProblemCode.INVALID_VALUE);
    }

    @Test
    void aFormatVersionOtherThanOneEndsReadingWithThatProblem() {
        Problem problem = problem("{ \"cairn\": 2, \"from\": { \"type\": \"A\" }, \"nonsense\": 1 }");

        assertThat(problem.code()).isEqualTo(ProblemCode.UNSUPPORTED_FORMAT_VERSION);
        assertThat(problem.path()).isEqualTo("@query");
        assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/cairn");
    }

    @Test
    void yamlFeaturesOutsideTheSubsetAreProblemsAtTheirPlace() {
        Problem anchor = unsupported("from: &t {type: A, as: a}\nsteps: []\n");
        Problem merge = unsupported("from: {type: A, as: a}\nsteps:\n  - where: {<<: {}}\n");
        Problem tag = unsupported("from: {type: A, as: a}\nlimit: !!int 5\n");
        Problem second = unsupported("from: {type: A, as: a}\n---\nfrom: {type: B, as: b}\n");
        Problem duplicate = problems("{\"from\": {\"type\": \"A\", \"as\": \"a\"}, \"from\": {\"type\": \"B\", \"as\": \"b\"}}")
                .getFirst();

        assertThat(anchor.path()).isEqualTo("@query.from");
        assertThat(merge.path()).isEqualTo("@query.steps.0");
        assertThat(tag.path()).isEqualTo("@query.limit");
        assertThat(second.path()).isEqualTo("@query");
        assertThat(duplicate.code()).isEqualTo(ProblemCode.DUPLICATE_KEY);
        assertThat(duplicate.path()).isEqualTo("@query");
    }

    /** The problem about a YAML feature outside the subset, of a text that has one. */
    private static Problem unsupported(String text) {
        return problems(text).stream().filter(found -> found.code() == ProblemCode.UNSUPPORTED_YAML_FEATURE)
                .findFirst().orElseThrow(() -> new AssertionError("No UNSUPPORTED_YAML_FEATURE problem in " + text));
    }

    @Test
    void conditionsThatNestTooDeepAreAProblemAndNoStackOverflow() {
        String nested = "{ \"hasValue\": { \"attribute\": \"x\", \"of\": \"a\" } }";
        for (int i = 0; i < 300; i++) {
            nested = "{ \"not\": " + nested + " }";
        }

        Problem problem = problem(step("{ \"where\": " + nested + " }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(problem.path()).isEqualTo("@query.steps.0");
        assertThat(problem.message()).contains("nest at most");
    }

    @Test
    void readingNeverThrows() {
        for (String text : List.of("null", "42", "\"text\"", "[]", "[[]]", "{", "}", "{\"from\": []}", "{\"from\": null}",
                "{\"from\": {}}", "{\"steps\": null}", "{\"from\": {\"type\": null, \"as\": null}}", "\u0000", "\t",
                "{\"from\": {\"type\": \"A\", \"as\": \"a\"}, \"steps\": [null, 1, [], {}, {\"where\": null}]}",
                "{\"from\": {\"type\": \"A\", \"as\": \"a\"}, \"columns\": [null], \"order\": [[]], \"groupBy\": [1], "
                        + "\"aggregates\": [\"x\"], \"limit\": 99999999999999999999}",
                "? [a]\n: b\n", "{\"from\": {\"type\": \"A\", \"as\": \"a\"}, \"steps\": [{\"traverse\": "
                        + "{\"from\": \"a\", \"name\": \"n\", \"as\": \"b\", \"maxDepth\": 99999999999}}]}")) {
            QueryReadResult result = Queries.read(text);

            assertThat(result).as(text).isNotNull();
            if (result instanceof QueryReadResult.Invalid invalid) {
                assertThat(invalid.problems()).as(text).isNotEmpty().allSatisfy(problem ->
                        assertThat(problem.location()).isPresent());
            }
        }
    }
}
