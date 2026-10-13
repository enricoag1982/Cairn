package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Objects;

/**
 * A purge that was applied (ADR-0024, design D4 of {@code add-purge}): the purge record that the store lists, which holds
 * the basis, the transaction time, the principal, the party acted for, the reason, and the IDs of the entities and
 * relationships that the purge removed. Recovery applies a purge by removing the IDs it lists.
 *
 * @param purge the record of the purge
 */
public record PurgeLogRecord(PurgeRecord purge) implements LogRecord {

    /**
     * Rejects a null purge record.
     */
    public PurgeLogRecord {
        Objects.requireNonNull(purge, "purge");
    }

    @Override
    public Basis basis() {
        return purge.basis();
    }

    @Override
    public Instant transactionTime() {
        return purge.transactionTime();
    }
}
