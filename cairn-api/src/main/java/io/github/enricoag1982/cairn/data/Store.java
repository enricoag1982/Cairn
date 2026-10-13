package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.util.List;

/**
 * A system of record: a schema, and the entities and relationships written under it, with history on two time axes
 * (ADR-0007, ADR-0008, capabilities {@code transactions}, {@code entity-versions}, {@code relationships} and
 * {@code views}).
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
     * the earlier receipt and records nothing; with other content it is rejected.
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
}
