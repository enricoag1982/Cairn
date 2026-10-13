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
     * <p>The IDs of what was removed are returned here, once, so that the caller can erase copies of the data outside the
     * store. The store does not keep them: its {@linkplain PurgeRecord purge record} holds numbers, and it holds digests
     * of the IDs to refuse them. Keep them no longer than the erasure needs.
     *
     * @param record        the record of the purge, as {@link Store#purges()} lists it
     * @param entities      the IDs of the entities that were removed: the ones the purge named, in the order given and
     *                      without repeats, and then the targets of their compositions that the purge took with them,
     *                      depth first, in the order in which they were reached
     * @param relationships the IDs of the relationships that were removed, in the order of their IDs
     */
    record Purged(PurgeRecord record, List<EntityId> entities, List<RelationshipId> relationships)
            implements PurgeResult {
        /**
         * Rejects null components and copies the lists.
         */
        public Purged {
            Objects.requireNonNull(record, "record");
            entities = List.copyOf(entities);
            relationships = List.copyOf(relationships);
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
