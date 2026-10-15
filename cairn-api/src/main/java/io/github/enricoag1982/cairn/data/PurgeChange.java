package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Objects;

/**
 * The change that a purge made (ADR-0029, capability {@code change-feed}): its purge record, with the numbers of entities
 * and relationships it removed. It names none of them: the entries of what a purge removes are gone from the changes of
 * the transactions that wrote it (ADR-0024).
 *
 * @param purge the purge record, as {@link Store#purges()} lists it
 */
public record PurgeChange(PurgeRecord purge) implements Change {

    /**
     * Rejects a null purge record.
     */
    public PurgeChange {
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
