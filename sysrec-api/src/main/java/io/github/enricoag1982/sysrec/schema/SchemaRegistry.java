package io.github.enricoag1982.sysrec.schema;

import java.time.Instant;
import java.util.List;

/**
 * Registers schema definitions and keeps the history of the stored schema in transaction time
 * (capability {@code schema-evolution}). Registrations are applied one at a time; reads never wait.
 */
public interface SchemaRegistry {

    /**
     * Registers the complete set of types of an origin.
     *
     * <p>The definition is validated together with the types of the other origins. A valid definition that changes
     * the stored schema compatibly is applied as a schema transaction. A definition equal to the stored one, or an
     * older version of the origin that the stored definition evolved from compatibly, leaves the schema unchanged.
     * Anything else is rejected with every problem found.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @return the outcome
     */
    RegistrationResult register(Origin origin, SchemaDefinition definition);

    /**
     * Validates a definition as {@link #register(Origin, SchemaDefinition)} would, without checking compatibility
     * and without changing anything.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @return every problem found; empty if the definition is valid
     */
    List<Problem> validate(Origin origin, SchemaDefinition definition);

    /**
     * Returns the latest schema.
     *
     * @return the schema after the latest schema transaction, or an empty schema
     */
    Schema current();

    /**
     * Returns the schema as it was at a basis.
     *
     * @param basis the basis
     * @return the schema after the latest schema transaction at or before the basis, or an empty schema
     */
    Schema asOf(Basis basis);

    /**
     * Returns the schema as it was at a transaction time.
     *
     * @param transactionTime the transaction time
     * @return the schema after the latest schema transaction at or before that time, or an empty schema
     */
    Schema asOf(Instant transactionTime);

    /**
     * Lists every applied schema transaction.
     *
     * @return the transactions, oldest first
     */
    List<SchemaTransaction> history();
}
