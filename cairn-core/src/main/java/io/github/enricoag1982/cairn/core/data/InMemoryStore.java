package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A store that keeps everything in memory (design D3 to D7 of {@code add-entity-versions}, D2 and D3 of
 * {@code add-entity-rules}, D2 and D3 of {@code add-relationships}, ADR-0007, ADR-0008). Use {@code Stores.inMemory}
 * to create one.
 *
 * <p>One writer commits at a time, holding the writer lock of the store's {@link CommitSequence}, which the schema
 * registry shares. A commit applies the writes to working copies, validates the result, runs the rules, and only then
 * appends the new timelines, records the transaction, and publishes its basis. A dry run does the same up to the append
 * and then throws everything away. Readers take no lock: they read through {@linkplain StoreView views}, which see only
 * data at or below the basis that was published when they were created.
 *
 * <p>While the store runs rules, in a commit or a dry run, it refuses writes: a rule that commits, runs dry or
 * registers a schema in the store gets an {@code IllegalStateException}, and its transaction is rejected.
 */
public final class InMemoryStore implements Store {

    /** A committed transaction with an idempotency key, and the receipt it was given. */
    private record Keyed(Transaction transaction, Receipt receipt) {
    }

    private final CommitSequence sequence;
    private final InMemorySchemaRegistry registry;
    private final ConcurrentSkipListMap<EntityId, StoredEntity> entities = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<RelationshipId, StoredRelationship> relationships =
            new ConcurrentSkipListMap<>();
    private final CopyOnWriteArrayList<TransactionRecord> records = new CopyOnWriteArrayList<>();
    private final ValueChecks checks = new ValueChecks();
    private final Rules rules;
    /** The idempotency keys of committed transactions; guarded by the writer lock. */
    private final Map<String, Keyed> keys = new HashMap<>();
    /** The ID generator of the store, for entities and relationships alike; guarded by the writer lock. */
    private IdGenerator generator;

    /**
     * Creates an empty store.
     *
     * @param clock         the source of transaction times
     * @param generator     the ID generator of the store, which the store owns from now on
     * @param rules         the rules with code, which the store runs at commit; their descriptions are available rules
     * @param declaredRules the descriptions of rules that the store does not have the code of; types of the schema may
     *                      name them, but a write that would need to run one is rejected
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two
     *                                  rules, with code or declared, have the same name and version
     * @throws NullPointerException     if a rule has no descriptor
     */
    public InMemoryStore(InstantSource clock, IdGenerator generator, Collection<? extends Rule> rules,
            Collection<RuleDescriptor> declaredRules) {
        this.sequence = new CommitSequence(Objects.requireNonNull(clock, "clock"));
        this.generator = Objects.requireNonNull(generator, "generator");
        this.rules = new Rules(rules);
        List<RuleDescriptor> available = new ArrayList<>(this.rules.descriptors());
        available.addAll(declaredRules);
        this.registry = new InMemorySchemaRegistry(sequence, available);
    }

    @Override
    public SchemaRegistry schema() {
        return registry;
    }

    @Override
    public CommitResult commit(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return sequence.underWriterLock(() -> {
            Keyed known = known(transaction);
            if (known != null) {
                return known.transaction().equals(transaction)
                        ? new CommitResult.Committed(known.receipt())
                        : new CommitResult.Rejected(List.of(reused(transaction)));
            }
            Preparation preparation = prepare(transaction);
            if (!preparation.problems().isEmpty()) {
                return new CommitResult.Rejected(preparation.problems());
            }
            return new CommitResult.Committed(record(transaction, preparation));
        });
    }

    @Override
    public DryRunResult dryRun(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return sequence.underWriterLock(() -> {
            Keyed known = known(transaction);
            if (known != null) {
                return known.transaction().equals(transaction)
                        ? new DryRunResult.WouldCommit()
                        : new DryRunResult.Rejected(List.of(reused(transaction)));
            }
            Preparation preparation = prepare(transaction);
            return preparation.problems().isEmpty()
                    ? new DryRunResult.WouldCommit()
                    : new DryRunResult.Rejected(preparation.problems());
        });
    }

    @Override
    public View view(Coordinates coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        Basis latest = sequence.latestBasis();
        Basis basis = switch (coordinates.knownAt()) {
            case KnownAt.Latest ignored -> latest;
            case KnownAt.AtBasis(Basis requested) -> requested.compareTo(latest) > 0 ? latest
                    : requested.compareTo(Basis.NONE) < 0 ? Basis.NONE : requested;
            case KnownAt.AtTransactionTime(Instant time) -> basisAt(time, latest);
        };
        return new StoreView(coordinates.effectiveAt(), basis, new SchemaIndex(registry.asOf(basis)), entities,
                relationships);
    }

    @Override
    public Basis latestBasis() {
        return sequence.latestBasis();
    }

    @Override
    public List<TransactionRecord> transactions() {
        Basis latest = sequence.latestBasis();
        return records.stream().filter(record -> record.basis().compareTo(latest) <= 0).toList();
    }

    /** The committed transaction that used the transaction's idempotency key, if any. Called with the lock held. */
    private Keyed known(Transaction transaction) {
        return transaction.idempotencyKey().map(keys::get).orElse(null);
    }

    private static Problem reused(Transaction transaction) {
        String key = transaction.idempotencyKey().orElseThrow();
        return Problems.of(ProblemCode.IDEMPOTENCY_KEY_REUSED, Preparation.TRANSACTION_PATH, "The idempotency key '"
                + key + "' was used by a committed transaction with other writes, principal, party or reason.",
                "the same writes, principal, party and reason as the committed transaction, or a new key",
                "another transaction with the same key", "Use a new idempotency key for a new transaction.");
    }

    /**
     * Applies and validates a transaction on working copies, with the basis and transaction time that a commit would
     * get and a copy of the ID generator. The sequence does not advance and the generator is not touched, so a caller
     * that drops the result leaves no trace. Called with the lock held.
     */
    private Preparation prepare(Transaction transaction) {
        return new Preparation(transaction, new SchemaIndex(registry.current()), entities, relationships,
                generator.copy(), sequence.next(), checks, rules, sequence).run();
    }

    /**
     * Makes a valid preparation the store's latest state: appends the new timelines of entities and relationships,
     * records the transaction and its idempotency key, keeps the ID generator's advanced copy, and publishes the basis
     * last, so that a reader that learns of the basis finds all of it. Called with the lock held.
     */
    private Receipt record(Transaction transaction, Preparation preparation) {
        CommitSequence.Commit commit = preparation.commit();
        for (Preparation.Change change : preparation.changes()) {
            switch (change.id()) {
                case EntityId id -> {
                    StoredEntity entity = change.created() ? new StoredEntity(id, change.type()) : entities.get(id);
                    entity.append(commit.basis(), change.timeline());
                    if (change.created()) {
                        entities.put(id, entity);
                    }
                }
                case RelationshipId id -> {
                    StoredRelationship relationship = change.created()
                            ? new StoredRelationship(id, change.type(), change.source(), change.target())
                            : relationships.get(id);
                    relationship.append(commit.basis(), change.timeline());
                    if (change.created()) {
                        relationships.put(id, relationship);
                    }
                }
            }
        }
        Receipt receipt = new Receipt(commit.basis(), commit.transactionTime(), preparation.temporaryIds(),
                preparation.created(), preparation.ended(), transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                transaction.idempotencyKey());
        records.add(new TransactionRecord(commit.basis(), commit.transactionTime(), transaction.principal(),
                transaction.onBehalfOf(), transaction.reason()));
        transaction.idempotencyKey().ifPresent(key -> keys.put(key, new Keyed(transaction, receipt)));
        generator = preparation.generator();
        sequence.publish(commit);
        return receipt;
    }

    /**
     * The basis of the latest transaction, of data or of the schema, recorded at or before a transaction time and
     * published by now; {@link Basis#NONE} if there is none.
     */
    private Basis basisAt(Instant time, Basis latest) {
        long found = Basis.NONE.value();
        for (TransactionRecord record : records) {
            if (record.basis().compareTo(latest) <= 0 && !record.transactionTime().isAfter(time)) {
                found = Math.max(found, record.basis().value());
            }
        }
        for (SchemaTransaction transaction : registry.history()) {
            if (transaction.basis().compareTo(latest) <= 0 && !transaction.transactionTime().isAfter(time)) {
                found = Math.max(found, transaction.basis().value());
            }
        }
        return new Basis(found);
    }
}
