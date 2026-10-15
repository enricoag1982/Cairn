package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.data.Transaction;
import java.time.Instant;
import java.util.Objects;

/**
 * A transaction and the instant at which a story records it. A test moves its store's clock to {@code recordedAt}
 * before it commits the transaction, so that the transaction time is the date the story gives.
 *
 * @param recordedAt  when the story records the transaction
 * @param transaction the transaction
 */
public record Recording(Instant recordedAt, Transaction transaction) {

    /**
     * Rejects null components.
     */
    public Recording {
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(transaction, "transaction");
    }
}
