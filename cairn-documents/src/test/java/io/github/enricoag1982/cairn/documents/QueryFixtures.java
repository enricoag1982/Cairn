package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;

/** Helpers for the tests that read queries. */
final class QueryFixtures {

    /** The query of RD-6, the total balance by ultimate parent, written as JSON in the form of the published schema. */
    static final String TOTAL_BALANCE = """
            {
              "cairn": 1,
              "from": { "type": "Account", "as": "a" },
              "steps": [
                { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } },
                { "traverse": { "from": "holder", "name": "ownedBy", "minDepth": 0, "maxDepth": 10, "as": "party" } },
                { "where": { "not": { "hasLink": { "of": "party", "name": "ownedBy" } } } }
              ],
              "groupBy": [ { "of": "party" } ],
              "aggregates": [ { "function": "sum", "attribute": "balance", "of": "a", "as": "total" } ]
            }
            """;

    private QueryFixtures() {
    }

    /** Reads a query that must have no problems. */
    static LocatedQuery read(String text) {
        QueryReadResult result = Queries.read(text);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(QueryReadResult.Read.class);
        return ((QueryReadResult.Read) result).query();
    }

    /** Reads a query that must have problems, and returns them; every one is located. */
    static List<Problem> problems(String text) {
        QueryReadResult result = Queries.read(text);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(QueryReadResult.Invalid.class);
        List<Problem> problems = ((QueryReadResult.Invalid) result).problems();
        assertThat(problems).as("every problem of a query is located").allSatisfy(problem ->
                assertThat(problem.location()).as(problem.toString()).isPresent());
        return problems;
    }

    /** Reads a query that must have exactly one problem, and returns it. */
    static Problem problem(String text) {
        List<Problem> problems = problems(text);
        assertThat(problems).as("problems of:%n%s", text).hasSize(1);
        return problems.getFirst();
    }

    /** The query of a text that must have no problems. */
    static Query query(String text) {
        return read(text).query();
    }
}
