package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.util.List;
import java.util.concurrent.Flow;

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
     * {@code IDEMPOTENCY_KEY_PURGED}, at {@code @transaction}, whatever its content.
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
     * their compositions that no other entity ever composed, with their own relationships. Afterwards every view, at
     * every basis and including views that were opened before the purge, and every timeline and query reads as if the
     * removed entities and relationships had never been recorded. The purge is recorded as a transaction of its own,
     * which {@link #purges()} lists with numbers, not IDs; the IDs of what was removed are returned to the caller, once.
     * A durable store has removed the data from its files too before the call returns. Rules do not run, because a purge
     * writes no versions, and they do not run again for what read the removed data either (ADR-0025): the store only
     * forgets what rules read of the removed entities and relationships.
     *
     * <p>The IDs of what was removed are never given to a new entity or relationship, and a write that supplies one is
     * rejected; the store keeps digests of them for that, and {@link #wasPurged(Id)} answers from them. The idempotency
     * keys of transactions that wrote removed data stay used, and a transaction that repeats one is rejected.
     *
     * <p>A purge is rejected, and nothing is changed and no basis is used, if it names an ID that no entity has
     * ({@code UNKNOWN_ENTITY}, at {@code @purge.<n>}, with {@code n} the position in the purge counted from 0), or if
     * an entity that remains would be left with fewer relationships of a type than the type's minimum at some point in
     * effective time in which the entity exists ({@code TOO_FEW_RELATIONSHIPS}, at the entity's ID, with the hint to
     * purge that entity too). That includes the target of a composition that another entity composed too: it is purged
     * with its sources only if every composition that ever had it as target has a source in the purge, and otherwise it
     * stays, and misses its source while the purge's entity composed it. Every problem is reported.
     *
     * <p>Once the purge has returned, every view reads the purged state. While it runs, a single lookup of a view never
     * finds a relationship whose end is missing, because the relationships are removed before the entities, but a view
     * that reads during a purge may see part of it: a relationship that was read before its end was removed has no end
     * when the end is read next, and a read that lists versions, such as {@code navigateTimeline}, may list a
     * relationship whose end is already gone. Applications should not rely on views across a purge.
     *
     * @param purge the entities to purge, with who asks, for whom and why; the principal, the party and the reason stay
     *              in the purge record, so they must not identify the data subject
     * @return the record of the purge with the IDs it removed, or every problem found
     * @throws IllegalStateException if a rule of this store calls it while the store runs rules, or the calling thread is
     *                               committing: a rule must not write
     */
    PurgeResult purge(Purge purge);

    /**
     * Lists the purge records: for each purge its basis, transaction time, principal, party acted for and reason, and
     * the numbers of entities and relationships it removed, but not what it removed. The records of the data
     * transactions that came before them are still listed by {@link #transactions()}, which lists data transactions
     * only.
     *
     * @return the purge records, oldest first
     */
    List<PurgeRecord> purges();

    /**
     * Tells whether an entity or relationship with this ID was purged. The store keeps digests of the IDs that purges
     * removed, not the IDs, so it can answer for an ID that it is asked about but cannot list them. A purged ID is
     * never given to a new entity or relationship, and is refused when a write supplies it. The ID of an entity and the
     * ID of a relationship are never the same, so the answer does not depend on which kind of ID is given.
     *
     * @param id the ID of an entity or relationship
     * @return {@code true} if a purge removed what had this ID
     */
    boolean wasPurged(Id id);

    /**
     * Reads the changes of the bases after a basis, in basis order (ADR-0029, capability {@code change-feed}): for each
     * basis one {@link Change}, a data transaction with what it changed, a schema registration or a purge. A consumer
     * that handled the changes up to a basis keeps it, and reads the next ones from it, after its own restart or the
     * store's, without missing or repeating a change.
     *
     * <p>The read goes up to the latest published basis when it starts, and takes no lock: commits do not wait for it. A
     * change read before a {@linkplain #purge(Purge) purge} can list more than the same change read after it, because a
     * purge removes the entries of what it removes.
     *
     * @param after the basis to read from, which is not included: {@link Basis#NONE} reads from the first change. A basis
     *              after the latest gives no change
     * @param limit the largest number of changes to return, at least 1
     * @return the changes at the bases after {@code after}, at most {@code limit} of them, oldest first; empty if there are
     *         none
     * @throws IllegalArgumentException if the limit is less than 1
     * @throws IllegalStateException    if the store is closed, for a store that can be
     */
    List<Change> changes(Basis after, int limit);

    /**
     * Follows the changes after a basis, as they are committed (ADR-0029, capability {@code change-feed}): a publisher of
     * the same changes that {@link #changes(Basis, int)} reads, which waits for further commits once it has delivered
     * them all. Each {@link Flow.Subscriber} that subscribes gets its own subscription, which starts after the basis that
     * was given, delivers each change once, in basis order, and only as many as the subscriber requested.
     *
     * <p>{@code onSubscribe} is called on the thread that subscribes. Everything else is called on a thread of the
     * subscription, never on the one that commits, one signal at a time. The subscription reads the changes at the pace of
     * the subscriber, in chunks of at most 256, and keeps no buffer of them: commits never wait for it, however slow
     * the subscriber is, and a subscriber that was away resumes from the basis of the last change it handled.
     *
     * <p>A subscription ends when the subscriber cancels it; when it requests fewer than one change, which ends it with
     * {@code onError} of an {@link IllegalArgumentException}; when {@code onNext} throws, which cancels it and goes no
     * further, so that commits go on; or when the store is closed, which completes it after the {@code onNext} that is
     * running returns. A subscription to a store that is closed or closing completes at once. A store that is not kept on
     * disk is never closed, so its subscriptions end only in the other ways. A read that fails ends the subscription with
     * {@code onError}.
     *
     * @param after the basis to follow from, which is not included: {@link Basis#NONE} follows from the first change
     * @return the publisher
     * @throws IllegalStateException if the store is closed, for a store that can be
     */
    Flow.Publisher<Change> changeFeed(Basis after);
}
