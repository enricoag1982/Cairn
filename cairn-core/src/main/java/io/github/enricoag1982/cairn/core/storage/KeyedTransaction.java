package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import java.util.Objects;

/**
 * A committed transaction with an idempotency key and the receipt it was given, which a snapshot file keeps so that the
 * same transaction submitted again gets the same receipt.
 *
 * @param transaction the transaction as submitted
 * @param receipt     its receipt
 */
public record KeyedTransaction(Transaction transaction, Receipt receipt) {

    /**
     * Rejects null components.
     */
    public KeyedTransaction {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(receipt, "receipt");
    }
}
