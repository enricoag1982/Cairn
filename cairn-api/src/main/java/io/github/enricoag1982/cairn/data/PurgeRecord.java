package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The record that a purge leaves in a store (ADR-0024, capability {@code purge}): a purge is a transaction of its own,
 * with a basis and a transaction time, a principal, a party acted for and a reason, and the IDs of everything it
 * removed. The records of earlier transactions stay, and so do these.
 *
 * @param basis           the basis the purge took; it names no version, because a purge writes none
 * @param transactionTime when the purge was recorded, with microsecond precision
 * @param principal       who asked for the purge
 * @param onBehalfOf      the party the principal acted for, if given
 * @param reason          why the entities were purged
 * @param entities        the IDs of the entities that were removed: the ones the purge named, in the order given and
 *                        without repeats, and then the targets of their compositions, depth first, in the order in which
 *                        they were reached
 * @param relationships   the IDs of the relationships that were removed, which are all the relationships from or to the
 *                        removed entities, in the order of their IDs
 */
public record PurgeRecord(
        Basis basis,
        Instant transactionTime,
        String principal,
        Optional<String> onBehalfOf,
        String reason,
        List<EntityId> entities,
        List<RelationshipId> relationships) {

    /**
     * Rejects null components and copies the lists.
     */
    public PurgeRecord {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        entities = List.copyOf(entities);
        relationships = List.copyOf(relationships);
    }
}
