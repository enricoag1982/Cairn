package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.util.List;

/**
 * A system of record: a schema, and the entities and relationships written under it, with history on two time axes
 * (ADR-0007, ADR-0008, capabilities {@code transactions}, {@code entity-versions}, {@code relationships},
 * {@code views} and {@code purge}).
 *
 * <p>One writer orders all commits, schema transactions and data transactions alike; they share one sequence of bases
 * and transaction times. Reads never wait: they go through {@linkplain View views}.
 */
public interface Store {

    /**
     * The store's own schema registry. Its schema transactions take their bases and transaction times from the
     * store's sequence, which data transactions share.
     *
     * @return the registry
     */
    SchemaRegistry schema();

    /**
     * Commits a transaction atomically: either every write is recorded or none is. The transaction is validated against
     * the state it would produce, and rejected with every problem found if any write is invalid.
     *
     * <p>A transaction with an idempotency key that a committed transaction already used, and that is equal to it, gets
     * the earlier receipt and records nothing; with other content it is rejected. A transaction with the key of one
     * that wrote data which a {@linkplain #purge(Purge) purge} removed is rejected with the problem
     * {@code IDEMPOTENCY_KEY_PURGED}, whatever its content.
     *
     * @param transaction the transaction
     * @return the receipt, or every problem found
     * @throws IllegalStateException if a rule of this store calls it while the store runs rules: a rule must not write
     */
    CommitResult commit(Transaction transaction);

    /**
     * Validates a transaction as {@link #commit(Transaction)} would, without committing it. A dry run records nothing,
     * uses no basis and reserves no ID.
     *
     * @param transaction the transaction
     * @return that the transaction would be committed, or every problem that committing would report
     * @throws IllegalStateException if a rule of this store calls it while the store runs rules: a rule must not
     *                               write, and a dry run is a commit that is not recorded
     */
    DryRunResult dryRun(Transaction transaction);

    /**
     * Creates a view bound to the given coordinates. The view reads as of the latest basis at this moment, unless the
     * coordinates name a basis or a transaction time.
     *
     * @param coordinates the effective time, and the basis or transaction time to read as of
     * @return the view
     */
    View view(Coordinates coordinates);

    /**
     * The basis of the latest committed transaction, whether it changed data or the schema.
     *
     * @return the basis; {@link Basis#NONE} if nothing was committed
     */
    Basis latestBasis();

    /**
     * Lists the rules that the store declares without having their code: types of the store's schema may name them,
     * but a transaction that writes an entity or a relationship whose type names one is rejected with the problem
     * {@code RULE_UNAVAILABLE} (capability {@code entity-rules}). With the rules of
     * {@code schema().availableRules()} that are not listed here, which the store runs, it tells a tool which rules
     * have code.
     *
     * @return the descriptors of the declared rules, sorted by name and then by version; empty if every available rule
     *         has code
     */
    List<RuleDescriptor> declaredRules();

    /**
     * Lists the committed data transactions with their principal, party acted for and reason, together with their basis
     * and transaction time. Schema transactions are listed by the schema registry.
     *
     * @return the transactions, oldest first
     */
    List<TransactionRecord> transactions();

    /**
     * Purges entities: removes them from all of history (ADR-0024, capability {@code purge}). It removes every version
     * of the named entities at every basis, every relationship from or to them with all its versions, and the targets of
     * their compositions, as end-dating ends them, with their own relationships. Afterwards every view, at every basis
     * and including views that were opened before the purge, and every timeline and query reads as if the removed
     * entities and relationships had never been recorded. The purge is recorded as a transaction of its own, which
     * {@link #purges()} lists; a durable store has removed the data from its files too before the call returns. Rules do
     * not run, because a purge writes no versions.
     *
     * <p>The IDs of what was removed are never given to a new entity or relationship, and a write that supplies one is
     * rejected. The idempotency keys of transactions that wrote removed data stay used, and a transaction that repeats
     * one is rejected.
     *
     * <p>A purge is rejected, and nothing is changed and no basis is used, if it names an ID that no entity has
     * ({@code UNKNOWN_ENTITY}, at {@code @purge.<n>}, with {@code n} the position in the purge counted from 0), or if
     * an entity that remains would be left with fewer relationships of a type than the type's minimum at some point in
     * effective time in which the entity exists ({@code TOO_FEW_RELATIONSHIPS}, at the entity's ID, with the hint to
     * purge that entity too). Every problem is reported.
     *
     * <p>A view that reads while a purge runs may see part of it, but never a relationship whose end is missing; once
     * the purge has returned, every view reads the purged state. Applications should not rely on views across a purge.
     *
     * @param purge the entities to purge, with who asks, for whom and why
     * @return the record of the purge, or every problem found
     * @throws IllegalStateException if a rule of this store calls it while the store runs rules: a rule must not write
     */
    PurgeResult purge(Purge purge);

    /**
     * Lists the purges, with their principal, party acted for, reason, basis and transaction time, and the IDs they
     * removed. The records of the data transactions that came before them are still listed by {@link #transactions()},
     * which lists data transactions only.
     *
     * @return the purges, oldest first
     */
    List<PurgeRecord> purges();
}
