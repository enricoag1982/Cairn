package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A data transaction that was committed: the transaction as submitted, which the store needs to recognize a retry with
 * the same idempotency key; its receipt; and what it did to each entity and relationship it changed.
 *
 * @param transaction the transaction as submitted: principal, party acted for, reason, idempotency key and writes
 * @param receipt     the receipt it was given: basis, transaction time, temporary IDs, created and ended IDs
 * @param effects     the entities and relationships that it created or changed, in the order of their first touch
 */
public record DataRecord(Transaction transaction, Receipt receipt, List<Effect> effects) implements LogRecord {

    /**
     * Rejects null components and copies the effects.
     */
    public DataRecord {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(receipt, "receipt");
        effects = List.copyOf(effects);
    }

    @Override
    public Basis basis() {
        return receipt.basis();
    }

    @Override
    public Instant transactionTime() {
        return receipt.transactionTime();
    }
}
