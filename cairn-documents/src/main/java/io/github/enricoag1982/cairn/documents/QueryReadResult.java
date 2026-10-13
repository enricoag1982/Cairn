package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link Queries#read(String)} (capability {@code queries}): the query, or every problem found in the
 * text.
 */
public sealed interface QueryReadResult {

    /**
     * The text is a query without problems.
     *
     * @param query the query and the locations of its parts
     */
    record Read(LocatedQuery query) implements QueryReadResult {
        /**
         * Rejects a null query.
         */
        public Read {
            Objects.requireNonNull(query, "query");
        }
    }

    /**
     * The text has problems, so there is no query. Every problem is located in the text.
     *
     * @param problems every problem found, in the order of the text; a syntax error or a format version other than 1 is
     *                 the only problem, because it stops reading
     */
    record Invalid(List<Problem> problems) implements QueryReadResult {
        /**
         * Copies the problem list.
         */
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
