package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs queries for the views of the engine (design D3 of {@code add-queries-and-aggregation}). Every view implements
 * {@link View#query} by calling it, so that all of them share one evaluator, which works through the view's own reads:
 * {@code schema}, {@code entities} (or {@code entity}, for a start from given entities), {@code navigate} and
 * {@code traverse}.
 *
 * <p>A query is checked against the view's schema first, and against its clearance if the view is restricted (ADR-0036),
 * with every problem reported and nothing read; then its steps apply in order to lists of rows. Nothing is cached between
 * queries.
 */
public final class QueryRunner {

    private QueryRunner() {
    }

    /**
     * Runs a query against a view.
     *
     * @param view  the view, which fixes the coordinates the query reads at
     * @param query the query
     * @return the rows, or the problems of a query that does not fit the view's schema
     */
    public static QueryResult run(View view, Query query) {
        Objects.requireNonNull(view, "view");
        return run(view, query, view.clearance().map(Withholding::of));
    }

    /**
     * Runs a query against a view that says what it withholds itself, such as a view that also withholds what the
     * store has classified since its basis (ADR-0036).
     *
     * @param view        the view, which fixes the coordinates the query reads at
     * @param query       the query
     * @param withholding what the view withholds; empty for a view that is not restricted
     * @return the rows, or the problems of a query that does not fit the view's schema or names what the view withholds
     */
    public static QueryResult run(View view, Query query, Optional<Withholding> withholding) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(withholding, "withholding");
        Checker.Checked checked = Checker.check(view.schema(), query, withholding);
        if (!checked.problems().isEmpty()) {
            return new QueryResult.Invalid(checked.problems());
        }
        return Executor.run(view, checked.plan());
    }
}
