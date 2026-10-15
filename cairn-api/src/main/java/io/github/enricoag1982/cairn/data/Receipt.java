package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The proof that a transaction was committed (capability {@code transactions}): when and at which basis it was
 * recorded, which entities and relationships it created and which ones the composition of its writes ended, which
 * entities its unique keys named, and who and why it recorded.
 *
 * <p>A transaction with an idempotency key that is submitted again gets the receipt of the first submission.
 *
 * @param basis           the basis the transaction produced
 * @param transactionTime when the transaction was recorded, with microsecond precision
 * @param temporaryIds    the ID of every entity or relationship the transaction named with a temporary ID, in the
 *                        order of the writes; an {@link EntityId} or a {@link RelationshipId}
 * @param created         the ID of every entity and relationship the transaction created, in the order of the writes
 * @param ended           the ID of every relationship and entity that the engine ended because a composition ends with
 *                        its source (capability {@code relationships}), and that none of the transaction's own
 *                        writes ended, in the order in which the cascades reached them, depth first: for each entity
 *                        that a write ended, in the order of the writes, each of its composition relationships in the
 *                        order of their IDs, every one followed by its target and then by the compositions of that
 *                        target in the same way, before the next relationship; each ID once
 * @param keys            what each {@linkplain UniqueKey unique key} that a write names resolved to (ADR-0026), in the
 *                        order of the writes, and, within a relate, the source before the target; empty if no write names
 *                        a key
 * @param principal       who submitted the transaction
 * @param onBehalfOf      the party the principal acted for, if given
 * @param reason          why the transaction was submitted, if given
 * @param idempotencyKey  the idempotency key of the transaction, if given
 */
public record Receipt(
        Basis basis,
        Instant transactionTime,
        Map<TemporaryId, Id> temporaryIds,
        List<Id> created,
        List<Id> ended,
        List<KeyResolution> keys,
        String principal,
        Optional<String> onBehalfOf,
        Optional<String> reason,
        Optional<String> idempotencyKey) {

    /**
     * Creates the receipt of a transaction that names no unique key.
     *
     * @param basis           the basis the transaction produced
     * @param transactionTime when the transaction was recorded
     * @param temporaryIds    the IDs of what the transaction named with temporary IDs
     * @param created         the IDs of what the transaction created
     * @param ended           the IDs of what compositions ended
     * @param principal       who submitted the transaction
     * @param onBehalfOf      the party the principal acted for, if given
     * @param reason          why the transaction was submitted, if given
     * @param idempotencyKey  the idempotency key of the transaction, if given
     */
    public Receipt(Basis basis, Instant transactionTime, Map<TemporaryId, Id> temporaryIds, List<Id> created,
            List<Id> ended, String principal, Optional<String> onBehalfOf, Optional<String> reason,
            Optional<String> idempotencyKey) {
        this(basis, transactionTime, temporaryIds, created, ended, List.of(), principal, onBehalfOf, reason,
                idempotencyKey);
    }

    /**
     * Rejects null components and copies the collections.
     */
    public Receipt {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        temporaryIds = Copies.map(temporaryIds, "temporaryIds");
        created = List.copyOf(created);
        ended = List.copyOf(ended);
        keys = List.copyOf(keys);
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
