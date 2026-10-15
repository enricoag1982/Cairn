package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A purge that was applied (ADR-0024, design D4 of {@code add-purge}): the purge record that the store lists, which holds
 * the basis, the transaction time, the principal, the party acted for, the reason, and the numbers of entities and
 * relationships that the purge removed, and the {@linkplain IdDigests digests} of the IDs of everything it removed.
 * Recovery applies a purge by removing the entities and relationships whose IDs have these digests; the log never holds
 * the IDs of what was purged.
 *
 * @param purge   the record of the purge
 * @param removed the digests of the IDs of the entities and relationships that were removed, in the order of the digests
 */
public record PurgeLogRecord(PurgeRecord purge, List<String> removed) implements LogRecord {

    /**
     * Rejects null components and copies the digests.
     */
    public PurgeLogRecord {
        Objects.requireNonNull(purge, "purge");
        removed = List.copyOf(removed);
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
