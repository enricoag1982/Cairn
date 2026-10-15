package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.DataRecord;
import io.github.enricoag1982.cairn.core.storage.Effect;
import io.github.enricoag1982.cairn.core.storage.History;
import io.github.enricoag1982.cairn.core.storage.KeyedTransaction;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.PurgeLogRecord;
import io.github.enricoag1982.cairn.core.storage.Recovery;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a store back the way a log and a snapshot file recorded it (design D4 of {@code add-durable-commit-log} and of
 * {@code add-purge}, D6 of {@code refactor-storage-onto-key-value-store}): the schema transactions, the timelines of
 * entities and relationships, which it writes to the {@link Ledger} as a commit does, the transaction records, the
 * idempotency keys with their receipts, and the purges: the purge records of a snapshot file with the digests of the
 * purged IDs and the purged keys, and those of the log, which remove the entities and relationships whose IDs have the
 * digests they list. It applies what it is given and checks nothing but that a change finds what it changes: it never
 * validates, runs rules, or asks the clock or the ID generator. Used once, on an empty store, by one thread, before the
 * store is used.
 */
final class Restorer implements Recovery.Replay {

    private final InMemoryStore store;
    /** The schema that {@link #index} was made for. */
    private Schema indexedSchema;
    private SchemaIndex index;

    Restorer(InMemoryStore store) {
        this.store = store;
    }

    @Override
    public void schema(SchemaRecord record) {
        store.registry().restore(record);
    }

    @Override
    public void transaction(TransactionRecord record) {
        store.records().add(record);
    }

    @Override
    public void keyed(KeyedTransaction keyed) {
        String key = keyed.transaction().idempotencyKey().orElseThrow(() -> new IllegalStateException(
                "A snapshot holds a transaction without an idempotency key as one that has a key."));
        store.keys().put(key, new InMemoryStore.Keyed(keyed.transaction(), keyed.receipt()));
    }

    @Override
    public void purge(PurgeRecord record) {
        store.restorePurge(record);
    }

    @Override
    public void purgedId(String digest) {
        store.purgedDigests().add(digest);
    }

    @Override
    public void purgedKey(String key) {
        store.purgedKeys().add(key);
    }

    /**
     * The schema as it was at a basis, which says what unique attributes the types had when the data was written. The
     * index of the last schema that was asked for is kept: a log or a snapshot file asks for the same one many times.
     */
    private SchemaIndex indexAt(Basis basis) {
        Schema schema = store.registry().asOf(basis);
        if (schema != indexedSchema) {
            indexedSchema = schema;
            index = new SchemaIndex(schema);
        }
        return index;
    }

    @Override
    public void history(History history) {
        boolean created = true;
        for (History.Version version : history.versions()) {
            Preparation.Change change = new Preparation.Change(history.id(), history.type(),
                    history.source().orElse(null), history.target().orElse(null), created,
                    LogRecords.timeline(version.segments()));
            store.ledger().write(version.basis(), List.of(change), indexAt(version.basis()));
            created = false;
        }
    }

    @Override
    public void record(LogRecord record) {
        switch (record) {
            case SchemaRecord schema -> store.registry().restore(schema);
            case DataRecord data -> restore(data);
            case PurgeLogRecord purge -> store.replayPurge(purge);
        }
    }

    private void restore(DataRecord data) {
        Basis basis = data.basis();
        List<Preparation.Change> changes = new ArrayList<>();
        for (Effect effect : data.effects()) {
            if (!effect.created() && store.ledger().held(effect.id()).isEmpty()) {
                throw new IllegalStateException("The record of basis " + basis.value() + " changes " + effect.id()
                        + ", which no earlier record created.");
            }
            changes.add(new Preparation.Change(effect.id(), effect.type(), effect.source().orElse(null),
                    effect.target().orElse(null), effect.created(), LogRecords.timeline(effect.segments())));
        }
        store.ledger().write(basis, changes, indexAt(basis));
        store.records().add(new TransactionRecord(basis, data.transactionTime(), data.transaction().principal(),
                data.transaction().onBehalfOf(), data.transaction().reason()));
        data.transaction().idempotencyKey()
                .ifPresent(key -> store.keys().put(key, new InMemoryStore.Keyed(data.transaction(), data.receipt())));
    }
}
