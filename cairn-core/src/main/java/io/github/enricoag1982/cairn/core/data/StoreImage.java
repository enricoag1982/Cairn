package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.History;
import io.github.enricoag1982.cairn.core.storage.KeyedTransaction;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
import io.github.enricoag1982.cairn.core.storage.Snapshots;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Reads what a snapshot file holds from a running store, at one basis, without the writer lock (design D6 of
 * {@code add-durable-commit-log}). What is published at or below a basis never changes, and a writer only adds under
 * higher bases, so the store can go on committing while the snapshot is written; whatever a commit in progress has added
 * above the basis is left out.
 *
 * <p>A purge is the exception: it removes what was published (ADR-0024). The durable store therefore writes no snapshot
 * file while a purge runs, and a snapshot file holds the purge records, the digests of the purged IDs and the purged
 * idempotency keys besides the data that remains, so that the purged IDs and keys stay unusable without the IDs being
 * kept.
 */
final class StoreImage {

    private StoreImage() {
    }

    /**
     * The content of a snapshot file of the store's latest state.
     *
     * @param store the store
     * @return what to write; the histories of entities and relationships are read lazily, while the file is written
     */
    static Snapshots.Content of(InMemoryStore store) {
        Basis basis = store.sequence().latestBasis();
        List<SchemaRecord> schema = store.registry().records().stream()
                .filter(record -> record.basis().compareTo(basis) <= 0)
                .toList();
        List<TransactionRecord> transactions = store.records().stream()
                .filter(record -> record.basis().compareTo(basis) <= 0)
                .toList();
        List<KeyedTransaction> keyed = store.keys().values().stream()
                .filter(entry -> entry.receipt().basis().compareTo(basis) <= 0)
                .sorted(Comparator.comparing(entry -> entry.receipt().basis()))
                .map(entry -> new KeyedTransaction(entry.transaction(), entry.receipt()))
                .toList();
        List<PurgeRecord> purges = store.purges().stream()
                .filter(record -> record.basis().compareTo(basis) <= 0)
                .toList();
        List<String> purgedIds = store.purgedDigests().stream().sorted().toList();
        List<String> purgedKeys = store.purgedKeys().stream().sorted().toList();
        Instant lastTime = null;
        for (SchemaRecord record : schema) {
            lastTime = later(lastTime, record.transactionTime());
        }
        for (TransactionRecord record : transactions) {
            lastTime = later(lastTime, record.transactionTime());
        }
        for (PurgeRecord record : purges) {
            lastTime = later(lastTime, record.transactionTime());
        }
        return new Snapshots.Content(basis, Optional.ofNullable(lastTime), schema, transactions, keyed, purges,
                purgedIds, purgedKeys, () -> histories(store, basis));
    }

    private static Instant later(Instant first, Instant second) {
        return first == null || second.isAfter(first) ? second : first;
    }

    /** The entity and relationship histories at or below the basis, entities first, each in the order of their IDs. */
    private static Iterator<History> histories(InMemoryStore store, Basis basis) {
        List<Iterator<History>> parts = new ArrayList<>();
        parts.add(store.entities().values().stream()
                .map(entity -> history(entity, basis, Optional.empty(), Optional.empty()))
                .flatMap(Optional::stream)
                .iterator());
        parts.add(store.relationships().values().stream()
                .map(relationship -> history(relationship, basis, Optional.of(relationship.source()),
                        Optional.of(relationship.target())))
                .flatMap(Optional::stream)
                .iterator());
        return new Iterator<>() {
            private int part;

            @Override
            public boolean hasNext() {
                while (part < parts.size() && !parts.get(part).hasNext()) {
                    part++;
                }
                return part < parts.size();
            }

            @Override
            public History next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return parts.get(part).next();
            }
        };
    }

    private static Optional<History> history(Stored<?> stored, Basis basis, Optional<EntityId> source,
            Optional<EntityId> target) {
        NavigableMap<Long, Timeline> versions = stored.versionsUpTo(basis);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        List<History.Version> list = new ArrayList<>();
        for (Map.Entry<Long, Timeline> version : versions.entrySet()) {
            list.add(new History.Version(new Basis(version.getKey()), LogRecords.segments(version.getValue())));
        }
        return Optional.of(new History(stored.id(), stored.type(), source, target, list));
    }
}
