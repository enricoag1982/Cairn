package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The record that a purge leaves in a store (ADR-0024, capability {@code purge}): a purge is a transaction of its own,
 * with a basis and a transaction time, a principal, a party acted for and a reason, and the numbers of entities and
 * relationships it removed. It says that a purge happened, not what was purged: the IDs go to the caller of the purge
 * once, in the {@linkplain PurgeResult.Purged result}, and the store keeps only digests of them, which it uses to refuse
 * them (see {@link Store#wasPurged(Id)}). The records of earlier transactions stay.
 *
 * <p>The principal, the party acted for and the reason are kept for as long as the store lives, so they must not name
 * the person whose data was purged (see {@link Purge}).
 *
 * @param basis           the basis the purge took; it names no version, because a purge writes none
 * @param transactionTime when the purge was recorded, with microsecond precision
 * @param principal       who asked for the purge
 * @param onBehalfOf      the party the principal acted for, if given
 * @param reason          why the entities were purged
 * @param entities        the number of entities that were removed: the ones the purge named and the targets of their
 *                        compositions that it took with them
 * @param relationships   the number of relationships that were removed, which are all the relationships from or to the
 *                        removed entities
 */
public record PurgeRecord(
        Basis basis,
        Instant transactionTime,
        String principal,
        Optional<String> onBehalfOf,
        String reason,
        int entities,
        int relationships) {

    /**
     * Rejects null components and negative numbers.
     *
     * @throws IllegalArgumentException if a number is negative
     */
    public PurgeRecord {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        Objects.requireNonNull(reason, "reason");
        if (entities < 0 || relationships < 0) {
            throw new IllegalArgumentException("A purge removes a number of entities and relationships that is not "
                    + "negative, but it removed " + entities + " and " + relationships + ".");
        }
    }
}
