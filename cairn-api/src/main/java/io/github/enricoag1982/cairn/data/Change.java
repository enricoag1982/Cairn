package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;

/**
 * What one basis of a store changed (ADR-0029, capability {@code change-feed}): a store has exactly one change for each of
 * its bases, in basis order and without gaps. A change is one of three kinds:
 *
 * <ul>
 *   <li>a {@link TransactionChange} for a data transaction, with its transaction record and the entities and
 *       relationships it changed;
 *   <li>a {@link SchemaChange} for a schema registration, with its schema transaction;
 *   <li>a {@link PurgeChange} for a purge, with its purge record.
 * </ul>
 *
 * <p>A change names what changed, never the values: a consumer that needs values reads them through views at the
 * change's basis and at the basis before it, as of any effective time in the changed periods. Dry runs, rejected
 * transactions and registrations that change nothing use no basis and leave no change.
 *
 * <p>Read changes with {@link Store#changes(Basis, int)}, or follow them with {@link Store#changeFeed(Basis)}.
 */
public sealed interface Change permits TransactionChange, SchemaChange, PurgeChange {

    /**
     * The basis that the transaction, registration or purge produced.
     *
     * @return the basis
     */
    Basis basis();

    /**
     * When the transaction, registration or purge was recorded.
     *
     * @return the transaction time, with microsecond precision
     */
    Instant transactionTime();
}
