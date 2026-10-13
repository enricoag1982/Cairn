package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.DataRecord;
import io.github.enricoag1982.cairn.core.storage.Effect;
import io.github.enricoag1982.cairn.core.storage.History;
import io.github.enricoag1982.cairn.core.storage.KeyedTransaction;
import io.github.enricoag1982.cairn.core.storage.LogRecord;
import io.github.enricoag1982.cairn.core.storage.Recovery;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.util.Map;

/**
 * Puts a store back the way a log and a snapshot file recorded it (design D4 of {@code add-durable-commit-log}): the
 * schema transactions, the timelines of entities and relationships, the transaction records, and the idempotency keys
 * with their receipts. It applies what it is given and checks nothing but that a change finds what it changes: it never
 * validates, runs rules, or asks the clock or the ID generator. Used once, on an empty store, by one thread, before the
 * store is used.
 */
final class Restorer implements Recovery.Replay {

    private final InMemoryStore store;

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
    public void history(History history) {
        switch (history.id()) {
            case EntityId id -> {
                StoredEntity entity = new StoredEntity(id, history.type());
                for (History.Version version : history.versions()) {
                    entity.append(version.basis(), LogRecords.timeline(version.segments()));
                }
                store.entities().put(id, entity);
            }
            case RelationshipId id -> {
                StoredRelationship relationship = new StoredRelationship(id, history.type(),
                        history.source().orElseThrow(), history.target().orElseThrow());
                for (History.Version version : history.versions()) {
                    relationship.append(version.basis(), LogRecords.timeline(version.segments()));
                }
                store.relationships().put(id, relationship);
            }
        }
    }

    @Override
    public void record(LogRecord record) {
        switch (record) {
            case SchemaRecord schema -> store.registry().restore(schema);
            case DataRecord data -> restore(data);
        }
    }

    private void restore(DataRecord data) {
        Basis basis = data.basis();
        for (Effect effect : data.effects()) {
            switch (effect.id()) {
                case EntityId id -> {
                    StoredEntity entity = effect.created() ? new StoredEntity(id, effect.type())
                            : existing(store.entities(), id, basis);
                    entity.append(basis, LogRecords.timeline(effect.segments()));
                    store.entities().put(id, entity);
                }
                case RelationshipId id -> {
                    StoredRelationship relationship = effect.created()
                            ? new StoredRelationship(id, effect.type(), effect.source().orElseThrow(),
                                    effect.target().orElseThrow())
                            : existing(store.relationships(), id, basis);
                    relationship.append(basis, LogRecords.timeline(effect.segments()));
                    store.relationships().put(id, relationship);
                }
            }
        }
        store.records().add(new TransactionRecord(basis, data.transactionTime(), data.transaction().principal(),
                data.transaction().onBehalfOf(), data.transaction().reason()));
        data.transaction().idempotencyKey()
                .ifPresent(key -> store.keys().put(key, new InMemoryStore.Keyed(data.transaction(), data.receipt())));
    }

    /** The entity or relationship that a record changes, which an earlier record created. */
    private static <K, V> V existing(Map<K, V> stored, K id, Basis basis) {
        V found = stored.get(id);
        if (found == null) {
            throw new IllegalStateException("The record of basis " + basis.value() + " changes " + id
                    + ", which no earlier record created.");
        }
        return found;
    }
}
