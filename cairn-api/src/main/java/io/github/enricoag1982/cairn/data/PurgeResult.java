package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link Store#purge(Purge)} (capability {@code purge}).
 */
public sealed interface PurgeResult {

    /**
     * The entities were purged: what they were, their relationships and the targets of their compositions are gone from
     * every view at every basis, and the purge is on record.
     *
     * @param record the record of the purge, as {@link Store#purges()} lists it
     */
    record Purged(PurgeRecord record) implements PurgeResult {
        /**
         * Rejects a null record.
         */
        public Purged {
            Objects.requireNonNull(record, "record");
        }
    }

    /**
     * The purge was rejected: nothing was changed and no basis was used.
     *
     * @param problems every problem found
     */
    record Rejected(List<Problem> problems) implements PurgeResult {
        /**
         * Copies the problem list.
         */
        public Rejected {
            problems = List.copyOf(problems);
        }
    }
}
