package io.github.enricoag1982.cairn.schema;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An applied schema change, as kept in the schema history, with who submitted it, for whom and why (ADR-0017,
 * capability {@code schema-evolution}).
 *
 * @param basis           the basis the transaction produced
 * @param transactionTime when the transaction was recorded
 * @param origin          the origin that registered the change
 * @param schema          the schema after the transaction
 * @param principal       who submitted the registration; {@value Submission#UNKNOWN_PRINCIPAL} if it named none
 * @param onBehalfOf      the party the principal acted for, if given
 * @param reason          why the registration was submitted, if given
 */
public record SchemaTransaction(
        Basis basis,
        Instant transactionTime,
        Origin origin,
        Schema schema,
        String principal,
        Optional<String> onBehalfOf,
        Optional<String> reason) {

    /**
     * Rejects null components.
     */
    public SchemaTransaction {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a schema transaction of a registration that named no submission: the principal is
     * {@value Submission#UNKNOWN_PRINCIPAL}, and there is no party acted for and no reason.
     *
     * @param basis           the basis the transaction produced
     * @param transactionTime when the transaction was recorded
     * @param origin          the origin that registered the change
     * @param schema          the schema after the transaction
     */
    public SchemaTransaction(Basis basis, Instant transactionTime, Origin origin, Schema schema) {
        this(basis, transactionTime, origin, schema, Submission.UNKNOWN_PRINCIPAL, Optional.empty(), Optional.empty());
    }
}
