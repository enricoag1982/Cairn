package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link Store#commit(Transaction)} (capability {@code transactions}).
 */
public sealed interface CommitResult {

    /**
     * The transaction was committed: every write was recorded. A transaction that repeats an earlier one with the same
     * idempotency key is also reported as committed, with the earlier receipt, and records nothing.
     *
     * @param receipt the receipt
     */
    record Committed(Receipt receipt) implements CommitResult {
        /**
         * Rejects a null receipt.
         */
        public Committed {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * The transaction was rejected: nothing was recorded.
     *
     * @param problems every problem found
     */
    record Rejected(List<Problem> problems) implements CommitResult {
        /**
         * Copies the problem list.
         */
        public Rejected {
            problems = List.copyOf(problems);
        }
    }
}
