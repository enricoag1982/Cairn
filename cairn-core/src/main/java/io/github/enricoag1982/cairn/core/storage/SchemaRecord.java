package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import java.time.Instant;
import java.util.Objects;

/**
 * A registration that was applied to the schema.
 *
 * @param basis           the basis the schema transaction produced
 * @param transactionTime when it was recorded
 * @param submission      who submitted the registration, for whom and why
 * @param origin          the origin that registered the definition
 * @param definition     the complete definition of the origin, as it was submitted
 * @param identifiers     the identifiers that the registration assigned
 */
public record SchemaRecord(
        Basis basis,
        Instant transactionTime,
        Submission submission,
        Origin origin,
        SchemaDefinition definition,
        SchemaIdentifiers identifiers) implements LogRecord {

    /**
     * Rejects null components.
     */
    public SchemaRecord {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(identifiers, "identifiers");
    }
}
