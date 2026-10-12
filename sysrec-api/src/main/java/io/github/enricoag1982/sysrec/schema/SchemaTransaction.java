package io.github.enricoag1982.sysrec.schema;

import java.time.Instant;
import java.util.Objects;

/**
 * An applied schema change, as kept in the schema history (capability {@code schema-evolution}).
 *
 * @param basis           the basis the transaction produced
 * @param transactionTime when the transaction was recorded
 * @param origin          the origin that registered the change
 * @param schema          the schema after the transaction
 */
public record SchemaTransaction(Basis basis, Instant transactionTime, Origin origin, Schema schema) {

    /**
     * Rejects null components.
     */
    public SchemaTransaction {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(schema, "schema");
    }
}
