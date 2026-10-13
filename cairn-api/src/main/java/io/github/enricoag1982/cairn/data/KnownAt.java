package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Objects;

/**
 * The point in transaction time that a view reads as of (ADR-0008, capability {@code views}): the latest basis when the
 * view is created, a given basis, or the basis of the latest transaction recorded at or before a given transaction
 * time.
 */
public sealed interface KnownAt {

    /**
     * Reads as of the latest basis when the view is created.
     *
     * @return the point
     */
    static KnownAt latest() {
        return new Latest();
    }

    /**
     * Reads as of a basis.
     *
     * @param basis the basis
     * @return the point
     */
    static KnownAt basis(Basis basis) {
        return new AtBasis(basis);
    }

    /**
     * Reads as of a transaction time: the basis is that of the latest transaction recorded at or before it.
     *
     * @param transactionTime the transaction time
     * @return the point
     */
    static KnownAt transactionTime(Instant transactionTime) {
        return new AtTransactionTime(transactionTime);
    }

    /**
     * The latest basis when the view is created.
     */
    record Latest() implements KnownAt {
    }

    /**
     * A given basis.
     *
     * @param basis the basis
     */
    record AtBasis(Basis basis) implements KnownAt {
        /**
         * Rejects a null basis.
         */
        public AtBasis {
            Objects.requireNonNull(basis, "basis");
        }
    }

    /**
     * A given transaction time.
     *
     * @param transactionTime the transaction time
     */
    record AtTransactionTime(Instant transactionTime) implements KnownAt {
        /**
         * Rejects a null transaction time.
         */
        public AtTransactionTime {
            Objects.requireNonNull(transactionTime, "transactionTime");
        }
    }
}
