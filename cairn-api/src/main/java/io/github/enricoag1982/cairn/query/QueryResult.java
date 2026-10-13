package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What running a query against a {@linkplain io.github.enricoag1982.cairn.data.View view} gives (ADR-0023, capability
 * {@code queries}): rows, or the problems of a query that does not fit the view's schema. A query never throws for
 * that.
 */
public sealed interface QueryResult {

    /**
     * The rows of a query, in the order that the query and the data fix, whatever stores them.
     *
     * @param effectiveAt the effective time of the view that ran the query
     * @param basis       the basis of the view that ran the query
     * @param columns     the names of the columns, in order
     * @param rows        the rows, each with a cell for every column
     */
    record Rows(Instant effectiveAt, Basis basis, List<String> columns, List<Row> rows) implements QueryResult {

        /**
         * Rejects null components and copies the lists.
         */
        public Rows {
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(basis, "basis");
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /**
     * A query that does not fit the view's schema: nothing was read. Every problem is reported, with the path of the part
     * of the query it concerns: {@code @query.from}, {@code @query.steps.<n>}, {@code @query.groupBy.<n>},
     * {@code @query.aggregates.<n>}, {@code @query.columns.<n>} or {@code @query.order.<n>}, counted from 0.
     *
     * @param problems the problems, in the order of the parts of the query; at least one
     */
    record Invalid(List<Problem> problems) implements QueryResult {

        /**
         * Copies the problems, rejecting nulls.
         */
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
