package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A query that was read locates the problems that running it returns, by their {@code @query...} paths (design D4 of
 * {@code add-queries-and-aggregation}).
 */
class QueryLocationsTest {

    /** A query with a start type that does not exist, a relationship name that does not exist, and a grouping. */
    private static final String TEXT = """
            {
              "from": { "type": "Acount", "as": "a" },
              "steps": [
                { "where": { "hasValue": { "attribute": "balance", "of": "a" } } },
                { "where": { "compare": { "left": { "attribute": "balanse", "of": "a" }, "op": "gt",
                                          "right": { "value": 1200 } } } },
                { "follow": { "from": "a", "name": "HeldBye", "as": "holder" } }
              ],
              "groupBy": [ { "of": "a", "attribute": "currensy" } ],
              "aggregates": [ { "function": "sum", "attribute": "name", "of": "holder", "as": "total" } ],
              "columns": [ { "binding": "nobody" } ],
              "order": [ { "key": "nothing" } ],
              "limit": 5
            }
            """;

    /** A query with a start that is fine, and a misspelled attribute in the grouping and in the aggregates. */
    private static final String GROUPED = """
            {
              "from": { "type": "Account", "as": "a" },
              "steps": [ { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } } ],
              "groupBy": [ { "of": "a", "attribute": "currensy" }, { "of": "nobody" } ],
              "aggregates": [ { "function": "sum", "attribute": "balanse", "of": "a", "as": "total" },
                              { "function": "sum", "attribute": "name", "of": "holder", "as": "names" } ]
            }
            """;

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    private List<Problem> run(LocatedQuery query) {
        QueryResult result = reference.latest().query(query.query());
        assertThat(result).isInstanceOf(QueryResult.Invalid.class);
        return ((QueryResult.Invalid) result).problems();
    }

    @Test
    void theProblemsOfRunningAQueryAreLocatedAtTheirPartsOfTheText() {
        LocatedQuery query = read(TEXT);

        List<Problem> located = query.locate(run(query));

        assertThat(located).extracting(Problem::code, Problem::path,
                problem -> problem.location().orElseThrow().pointer()).containsExactly(
                        tuple(ProblemCode.UNKNOWN_ENTITY_TYPE, "@query.from", "/from"),
                        tuple(ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@query.steps.2", "/steps/2"),
                        tuple(ProblemCode.UNKNOWN_BINDING, "@query.columns.0", "/columns/0"),
                        tuple(ProblemCode.UNKNOWN_BINDING, "@query.order.0", "/order/0"));
    }

    @Test
    void theProblemsOfAGroupingAndItsAggregatesAreLocatedAtThem() {
        LocatedQuery query = read(GROUPED);

        List<Problem> located = query.locate(run(query));

        assertThat(located).extracting(Problem::code, Problem::path,
                problem -> problem.location().orElseThrow().pointer()).containsExactly(
                        tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.groupBy.0", "/groupBy/0"),
                        tuple(ProblemCode.UNKNOWN_BINDING, "@query.groupBy.1", "/groupBy/1"),
                        tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.aggregates.0", "/aggregates/0"),
                        tuple(ProblemCode.INVALID_AGGREGATE, "@query.aggregates.1", "/aggregates/1"));
        assertThat(located.get(3).location()).contains(locationOf(GROUPED, "{ \"function\": \"sum\", \"attribute\": "
                + "\"name\"", "/aggregates/1"));
    }

    @Test
    void aProblemIsLocatedAtTheLineAndColumnOfThePart() {
        LocatedQuery query = read(TEXT);

        List<Problem> located = query.locate(run(query));

        assertThat(located.get(0).location()).contains(locationOf(TEXT, "\"from\"", "/from"));
        assertThat(located.get(1).location()).contains(locationOf(TEXT, "{ \"follow\"", "/steps/2"));
        assertThat(located.get(3).location()).contains(locationOf(TEXT, "{ \"key\": \"nothing\" }", "/order/0"));
    }

    @Test
    void aProblemOfAnotherStepIsLocatedAtThatStep() {
        String text = """
                {
                  "from": { "type": "Account", "as": "a" },
                  "steps": [
                    { "where": { "hasValue": { "attribute": "balance", "of": "a" } } },
                    { "where": { "compare": { "left": { "attribute": "balanse", "of": "a" }, "op": "gt",
                                              "right": { "value": 1200 } } } }
                  ]
                }
                """;
        LocatedQuery query = read(text);

        List<Problem> located = query.locate(run(query));

        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
            assertThat(problem.path()).isEqualTo("@query.steps.1");
            assertThat(problem.location()).contains(locationOf(text, "{ \"where\": { \"compare\"", "/steps/1"));
        });
    }

    @Test
    void aPathThatNamesNoPartIsLocatedAtTheClosestPartThatTheTextHas() {
        LocatedQuery query = read(TEXT);
        Problem beyond = new Problem(ProblemCode.UNKNOWN_BINDING, "@query.steps.9", "No such step.");
        Problem whole = new Problem(ProblemCode.UNKNOWN_BINDING, "@query", "The query.");
        Problem nowhere = new Problem(ProblemCode.UNKNOWN_BINDING, "somewhere", "Something else.");
        Problem key = new Problem(ProblemCode.UNKNOWN_BINDING, "@query.limit", "The limit.");

        List<Problem> located = query.locate(List.of(beyond, whole, nowhere, key));

        assertThat(located).extracting(problem -> problem.location().orElseThrow().pointer())
                .containsExactly("/steps", "", "", "/limit");
        assertThat(query.locations().root().pointer()).isEmpty();
    }

    @Test
    void aProblemThatIsLocatedAlreadyKeepsItsLocation() {
        LocatedQuery query = read(TEXT);
        DocumentLocation elsewhere = new DocumentLocation("/elsewhere", 7, 3);
        Problem located = new Problem(ProblemCode.UNKNOWN_KEY, "@query.steps.0", "Located.", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(elsewhere));

        assertThat(query.locate(List.of(located))).containsExactly(located);
        assertThat(query.locations().locate(located)).isEqualTo(located);
    }

    @Test
    void aQueryWrittenAsYamlIsLocatedToo() {
        String yaml = """
                from:
                  type: Account
                  as: a
                steps:
                  - where:
                      hasValue: {attribute: balanse, of: a}
                """;
        LocatedQuery query = read(yaml);

        List<Problem> located = query.locate(run(query));

        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("@query.steps.0");
            assertThat(problem.location()).contains(locationOf(yaml, "where:", "/steps/0"));
        });
    }

    @Test
    void locationsAreValuesAndPrintForDiagnostics() {
        LocatedQuery first = read(TEXT);
        LocatedQuery second = read(TEXT);

        assertThat(first.locations()).isEqualTo(second.locations()).hasSameHashCodeAs(second.locations());
        assertThat(first.locations().toString()).startsWith("QueryLocations").contains("@query.steps.0");
        assertThat(first).isEqualTo(second);
    }
}
