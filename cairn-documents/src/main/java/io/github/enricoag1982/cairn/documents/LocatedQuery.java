package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * A query that was read from text: the query and where in the text each of its parts is (capability {@code queries}).
 *
 * @param query     the query
 * @param locations where its parts are in the text
 */
public record LocatedQuery(Query query, QueryLocations locations) {

    /**
     * Rejects null components.
     */
    public LocatedQuery {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(locations, "locations");
    }

    /**
     * Locates problems in this query's text: the problems that running the query returns. A problem about a step, a group
     * key, an aggregate, a column or an order is located at it; one about the start, or the limit, at its key. See
     * {@link QueryLocations} for the rules. A problem that is located already keeps its location.
     *
     * @param problems the problems, for example those of {@code QueryResult.Invalid}
     * @return the same problems, in the same order, each with its location
     */
    public List<Problem> locate(List<Problem> problems) {
        return locations.locate(problems);
    }
}
