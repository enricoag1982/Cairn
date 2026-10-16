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
     * store, and so is the entity that each key of the purge named, so that the caller learns which entity a key such as
     * an employee number was. The store keeps none of it: its {@linkplain PurgeRecord purge record} holds numbers, and it
     * holds digests of the IDs to refuse them, and it never keeps a key or its value (ADR-0033). Keep them no longer than
     * the erasure needs.
     *
     * @param record        the record of the purge, as {@link Store#purges()} lists it
     * @param entities      the IDs of the entities that were removed: the ones the purge named by ID, in the order given,
     *                      then the ones it named by key, in the order of the keys, without repeats, and then the targets
     *                      of their compositions that the purge took with them, depth first, in the order in which they
     *                      were reached
     * @param relationships the IDs of the relationships that were removed, in the order of their IDs
     * @param keys          the entity that each key of the purge named, one for each key, in the order of the purge's
     *                      keys; empty for a purge that names no key
     */
    record Purged(PurgeRecord record, List<EntityId> entities, List<RelationshipId> relationships,
            List<ResolvedKey> keys) implements PurgeResult {
        /**
         * Rejects null components and copies the lists.
         */
        public Purged {
            Objects.requireNonNull(record, "record");
            entities = List.copyOf(entities);
            relationships = List.copyOf(relationships);
            keys = List.copyOf(keys);
        }

        /**
         * Creates the result of a purge that named no key.
         *
         * @param record        the record of the purge
         * @param entities      the IDs of the entities that were removed
         * @param relationships the IDs of the relationships that were removed
         */
        public Purged(PurgeRecord record, List<EntityId> entities, List<RelationshipId> relationships) {
            this(record, entities, relationships, List.of());
        }
    }

    /**
     * What a {@linkplain PurgeKey key} of a purge named (ADR-0033): the key as the purge gave it, and the entity that it
     * resolved to, which the purge removed.
     *
     * @param key    the key, with its effective time if the purge gave one
     * @param entity the ID of the entity that the key named
     */
    record ResolvedKey(PurgeKey key, EntityId entity) {
        /**
         * Rejects null components.
         */
        public ResolvedKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(entity, "entity");
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
