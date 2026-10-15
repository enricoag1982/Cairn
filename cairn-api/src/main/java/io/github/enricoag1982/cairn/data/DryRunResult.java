package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;

/**
 * The outcome of {@link Store#dryRun(Transaction)} (capability {@code transactions}). A dry run records nothing, uses no
 * basis and reserves no ID.
 */
public sealed interface DryRunResult {

    /**
     * Committing the transaction now would succeed.
     */
    record WouldCommit() implements DryRunResult {
    }

    /**
     * Committing the transaction now would be rejected, with the same problems.
     *
     * @param problems every problem found
     */
    record Rejected(List<Problem> problems) implements DryRunResult {
        /**
         * Copies the problem list.
         */
        public Rejected {
            problems = List.copyOf(problems);
        }
    }
}
