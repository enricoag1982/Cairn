package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;

/**
 * What one committed transaction did, as the commit log records it (ADR-0022): a schema transaction or a data
 * transaction. Recovery applies these effects; it does not validate, run rules or ask a clock or an ID generator.
 */
public sealed interface LogRecord permits SchemaRecord, DataRecord {

    /**
     * The basis the transaction produced.
     *
     * @return the basis
     */
    Basis basis();

    /**
     * When the transaction was recorded.
     *
     * @return the transaction time, with microsecond precision
     */
    Instant transactionTime();
}
