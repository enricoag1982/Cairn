package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The change that a data transaction made (ADR-0029, capability {@code change-feed}): its transaction record, and every
 * entity and relationship whose timeline at the transaction's basis differs from its timeline at the basis before.
 * Composition cascades are listed like what the transaction names. A transaction that changed nothing, such as an empty
 * one, has an empty list.
 *
 * <p>A purge removes the entries of what it removes, and leaves the others and the transaction record, so a change read
 * after a purge can list less than it listed before.
 *
 * @param transaction the transaction record, with the principal, the party acted for and the reason
 * @param changed     what the transaction changed, ordered by ID compared as text, each ID once
 */
public record TransactionChange(TransactionRecord transaction, List<Changed> changed) implements Change {

    /**
     * Rejects null components, copies the list, and checks that its items are in the order of their IDs, each ID once.
     *
     * @throws IllegalArgumentException if the items are not ordered by ID, or an ID is listed twice
     */
    public TransactionChange {
        Objects.requireNonNull(transaction, "transaction");
        changed = List.copyOf(changed);
        for (int i = 1; i < changed.size(); i++) {
            if (changed.get(i - 1).id().value().compareTo(changed.get(i).id().value()) >= 0) {
                throw new IllegalArgumentException("The changed entities and relationships are ordered by ID, each "
                        + "once, but " + changed.get(i - 1).id().value() + " is followed by "
                        + changed.get(i).id().value() + ".");
            }
        }
    }

    @Override
    public Basis basis() {
        return transaction.basis();
    }

    @Override
    public Instant transactionTime() {
        return transaction.transactionTime();
    }
}
