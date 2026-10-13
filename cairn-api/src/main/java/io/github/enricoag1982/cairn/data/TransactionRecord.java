package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An entry of a store's transaction history: a committed data transaction with who and why (ADR-0017, capability
 * {@code transactions}).
 *
 * @param basis           the basis the transaction produced
 * @param transactionTime when the transaction was recorded, with microsecond precision
 * @param principal       who submitted the transaction
 * @param onBehalfOf      the party the principal acted for, if given
 * @param reason          why the transaction was submitted, if given
 */
public record TransactionRecord(
        Basis basis,
        Instant transactionTime,
        String principal,
        Optional<String> onBehalfOf,
        Optional<String> reason) {

    /**
     * Rejects null components.
     */
    public TransactionRecord {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
    }
}
