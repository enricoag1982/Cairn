package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.core.schema.Problems;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.PurgeLogRecord;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.data.Write;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
 *
 * <p>A {@linkplain #purge(Purge) purge} is the one thing that removes data (ADR-0024, design of {@code add-purge}). It
 * takes the writer lock, checks what it would remove, and only then removes it: relationships first and entities after
 * them, so that a reader never finds a relationship whose end is missing. Views read the store's maps and keep no
 * reference to what they read, so once the purge has returned every view, also one that was opened before it, reads the
 * purged state. The IDs of what was removed are kept, so that they are never given out or accepted again, and so are the
 * idempotency keys of transactions that wrote removed data, without their transactions.
 *
 * <p>A store can have a {@linkplain Journal journal}, as a durable store does (ADR-0022, design D5 of
 * {@code add-durable-commit-log}): a commit then writes what it did to the journal after it has prepared the
 * transaction and before it records it and publishes its basis, and a commit that cannot be written is not applied. The
 * package's other classes restore a store from what a log and a snapshot file hold, and read it for a snapshot file.
 * Without a journal the store behaves as it always did.
 */
public final class InMemoryStore implements Store {

    /** A committed transaction with an idempotency key, and the receipt it was given. */
    record Keyed(Transaction transaction, Receipt receipt) {
    }

    private final CommitSequence sequence;
    private final InMemorySchemaRegistry registry;
    private final ConcurrentSkipListMap<EntityId, StoredEntity> entities = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<RelationshipId, StoredRelationship> relationships =
            new ConcurrentSkipListMap<>();
    private final CopyOnWriteArrayList<TransactionRecord> records = new CopyOnWriteArrayList<>();
    private final ValueChecks checks = new ValueChecks();
    private final Rules rules;
    private final List<RuleDescriptor> declaredRules;
    /**
     * The idempotency keys of committed transactions. Only the writer, holding the writer lock, changes them; a snapshot
     * file is written from them without the lock, so the map is a concurrent one.
     */
    private final Map<String, Keyed> keys = new ConcurrentHashMap<>();
    /** The purges, oldest first; only the writer, holding the writer lock, adds to it. */
    private final CopyOnWriteArrayList<PurgeRecord> purges = new CopyOnWriteArrayList<>();
    /**
     * The IDs of the entities and relationships that purges removed: no new entity or relationship gets one, and a write
     * that supplies one is rejected. Only the writer changes it; a snapshot file reads the purges instead.
     */
    private final Set<String> purgedIds = ConcurrentHashMap.newKeySet();
    /**
     * The idempotency keys of transactions that wrote data which a purge removed. The store no longer holds those
     * transactions, so a transaction that repeats one of these keys is rejected. A key is in this set or in {@link #keys}.
     */
    private final Set<String> purgedKeys = ConcurrentHashMap.newKeySet();
    /** The ID generator of the store, for entities and relationships alike; guarded by the writer lock. */
    private IdGenerator generator;
    /** Where commits are written before they are recorded; null for a store that is not durable. */
    private volatile Journal journal;

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
        this.declaredRules = declaredRules.stream()
                .sorted(Comparator.comparing(RuleDescriptor::name).thenComparingInt(RuleDescriptor::version))
                .toList();
    }

    @Override
    public SchemaRegistry schema() {
        return registry;
    }

    @Override
    public List<RuleDescriptor> declaredRules() {
        return declaredRules;
    }

    @Override
    public CommitResult commit(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return sequence.underWriterLock(() -> {
            requireWritable();
            if (isPurged(transaction)) {
                return new CommitResult.Rejected(List.of(purged(transaction)));
            }
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
            if (isPurged(transaction)) {
                return new DryRunResult.Rejected(List.of(purged(transaction)));
            }
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

    /** Refuses a commit if the journal does: an earlier write failed, or the store is closed. Called with the lock held. */
    private void requireWritable() {
        Journal written = journal;
        if (written != null) {
            written.requireWritable();
        }
    }

    /** Tells whether the transaction's idempotency key belongs to a transaction that a purge removed. */
    private boolean isPurged(Transaction transaction) {
        return transaction.idempotencyKey().map(purgedKeys::contains).orElse(false);
    }

    private static Problem purged(Transaction transaction) {
        String key = transaction.idempotencyKey().orElseThrow();
        return Problems.of(ProblemCode.IDEMPOTENCY_KEY_PURGED, Preparation.TRANSACTION_PATH + ".idempotencyKey",
                "The idempotency key '" + key + "' was used by a transaction that wrote data which a purge removed, so "
                + "the store cannot tell whether this transaction is a retry, and recording it could bring the purged "
                + "data back.", "an idempotency key that no committed transaction used, or none", key,
                "Use a new idempotency key for a new transaction.");
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
        return new Preparation(transaction, new SchemaIndex(registry.current()), entities, relationships, purgedIds,
                generator.copy(), sequence.next(), checks, rules, sequence).run();
    }

    /**
     * Makes a valid preparation the store's latest state. A durable store first writes the commit to its journal, so that
     * nothing is applied if that fails; then the new timelines of entities and relationships are appended, the
     * transaction and its idempotency key are recorded, the ID generator's advanced copy is kept, and the basis is
     * published last, so that a reader that learns of the basis finds all of it. Called with the lock held.
     */
    private Receipt record(Transaction transaction, Preparation preparation) {
        CommitSequence.Commit commit = preparation.commit();
        Receipt receipt = new Receipt(commit.basis(), commit.transactionTime(), preparation.temporaryIds(),
                preparation.created(), preparation.ended(), transaction.principal(), transaction.onBehalfOf(),
                transaction.reason(), transaction.idempotencyKey());
        List<Preparation.Change> changes = preparation.changes();
        Journal written = journal;
        if (written != null) {
            written.append(LogRecords.dataRecord(transaction, receipt, changes));
        }
        for (Preparation.Change change : changes) {
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
        records.add(new TransactionRecord(commit.basis(), commit.transactionTime(), transaction.principal(),
                transaction.onBehalfOf(), transaction.reason()));
        transaction.idempotencyKey().ifPresent(key -> keys.put(key, new Keyed(transaction, receipt)));
        generator = preparation.generator();
        sequence.publish(commit);
        return receipt;
    }

    // ------------------------------------------------------------------ purges

    @Override
    public PurgeResult purge(Purge purge) {
        return purge(purge, () -> { });
    }

    @Override
    public List<PurgeRecord> purges() {
        Basis latest = sequence.latestBasis();
        return purges.stream().filter(record -> record.basis().compareTo(latest) <= 0).toList();
    }

    /**
     * Purges entities as {@link #purge(Purge)} does, and then runs an action, still holding the writer lock, once the
     * purge is applied and its basis published: a durable store makes the purge durable there, by writing a snapshot file
     * and deleting the older files (design D4 of {@code add-purge}).
     *
     * <p>Under the writer lock the purge is checked first, and a purge with problems changes nothing and uses no basis.
     * Otherwise it takes the next basis and transaction time of the sequence, as a commit does. A durable store writes
     * the purge record to its journal before anything is removed, so that nothing is removed if that fails. Then the
     * relationships and entities are removed, relationships first, the purged IDs and idempotency keys are kept, the
     * purge record is added, and the basis is published last.
     *
     * @param purge     the purge
     * @param published what to do after the purge was published, with the writer lock still held
     * @return the record of the purge, or every problem found
     */
    PurgeResult purge(Purge purge, Runnable published) {
        Objects.requireNonNull(purge, "purge");
        return sequence.<PurgeResult>underWriterLock(() -> {
            requireWritable();
            PurgeCheck.Outcome outcome = PurgeCheck.run(purge, new SchemaIndex(registry.current()), entities,
                    relationships, purgedIds);
            if (!outcome.problems().isEmpty()) {
                return new PurgeResult.Rejected(outcome.problems());
            }
            CommitSequence.Commit commit = sequence.next();
            PurgeRecord record = new PurgeRecord(commit.basis(), commit.transactionTime(), purge.principal(),
                    purge.onBehalfOf(), purge.reason(), outcome.entities(), outcome.relationships());
            Journal written = journal;
            if (written != null) {
                written.append(new PurgeLogRecord(record));
            }
            applyPurge(record);
            sequence.publish(commit);
            published.run();
            return new PurgeResult.Purged(record);
        });
    }

    /**
     * Removes what a purge record lists: the relationships first, then the entities, so that a reader never finds a
     * relationship whose end is missing; keeps their IDs as purged; drops the stored transactions of the idempotency
     * keys that wrote any of them and marks the keys purged; and adds the record. A commit does this after the check,
     * and recovery does it for a record of the log. Called with the lock held, or before the store is used.
     *
     * @throws IllegalStateException if the record lists an entity or relationship that the store does not hold
     */
    void applyPurge(PurgeRecord record) {
        for (RelationshipId id : record.relationships()) {
            if (relationships.remove(id) == null) {
                throw missing(record, id);
            }
        }
        for (EntityId id : record.entities()) {
            if (entities.remove(id) == null) {
                throw missing(record, id);
            }
        }
        Set<Id> removed = new HashSet<>(record.relationships());
        removed.addAll(record.entities());
        List<String> wrote = keys.entrySet().stream().filter(entry -> wrote(entry.getValue(), removed))
                .map(Map.Entry::getKey).toList();
        purgedKeys.addAll(wrote);
        wrote.forEach(keys::remove);
        restorePurge(record);
    }

    private static IllegalStateException missing(PurgeRecord record, Id id) {
        return new IllegalStateException("The purge of basis " + record.basis().value() + " removes " + id
                + ", which the store does not hold.");
    }

    /** Tells whether a committed transaction created, ended, patched, replaced or end-dated any of the removed IDs. */
    private static boolean wrote(Keyed keyed, Set<Id> removed) {
        Receipt receipt = keyed.receipt();
        if (receipt.created().stream().anyMatch(removed::contains)
                || receipt.ended().stream().anyMatch(removed::contains)) {
            return true;
        }
        for (Write write : keyed.transaction().writes()) {
            Object target = switch (write) {
                case Write.Create create -> null;
                case Write.Relate relate -> null;
                case Write.Patch patch -> patch.target();
                case Write.Replace replace -> replace.target();
                case Write.EndDate endDate -> endDate.target();
            };
            if (target != null && removed.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps a purge record and its IDs as purged, without removing anything: what a snapshot file holds of a purge, whose
     * data it does not hold.
     */
    void restorePurge(PurgeRecord record) {
        purges.add(record);
        record.entities().forEach(id -> purgedIds.add(id.value()));
        record.relationships().forEach(id -> purgedIds.add(id.value()));
    }

    // ------------------------------------------------------------------ durability

    /**
     * Makes the store write every commit and schema registration to a journal before it publishes it, and refuse them
     * while the journal refuses writes. Call it once, after recovery and before the store is used by other threads.
     */
    void attach(Journal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        registry.attach(journal);
    }

    /** Runs an action holding the writer lock, which no commit or registration holds while it runs. */
    <T> T underWriterLock(Supplier<? extends T> action) {
        return sequence.underWriterLock(action);
    }

    CommitSequence sequence() {
        return sequence;
    }

    InMemorySchemaRegistry registry() {
        return registry;
    }

    ConcurrentSkipListMap<EntityId, StoredEntity> entities() {
        return entities;
    }

    ConcurrentSkipListMap<RelationshipId, StoredRelationship> relationships() {
        return relationships;
    }

    CopyOnWriteArrayList<TransactionRecord> records() {
        return records;
    }

    Map<String, Keyed> keys() {
        return keys;
    }

    /** The idempotency keys whose transactions a purge removed. */
    Set<String> purgedKeys() {
        return purgedKeys;
    }

    /**
     * The basis of the latest transaction, of data, of the schema or a purge, recorded at or before a transaction time
     * and published by now; {@link Basis#NONE} if there is none.
     */
    private Basis basisAt(Instant time, Basis latest) {
        long found = Basis.NONE.value();
        for (TransactionRecord record : records) {
            if (record.basis().compareTo(latest) <= 0 && !record.transactionTime().isAfter(time)) {
                found = Math.max(found, record.basis().value());
            }
        }
        for (PurgeRecord record : purges) {
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
