package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.Trait;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two stores (design D8 of {@code add-durable-commit-log} and D5 of {@code add-purge}): the schema history, the
 * transaction records, the purge records, the receipts of idempotency keys, and, for every basis, the timelines of every entity and relationship and the views at
 * the given effective times. A durable store and an in-memory store that committed the same transactions must be
 * equivalent, before the durable store is reopened and after.
 *
 * <p>A store is {@linkplain #observe observed} into plain values, which stay valid after the store is closed, so that the
 * state of a store before it was closed can be compared with the state of the store that reopened its directory.
 */
final class StoreEquivalence {

    /** The schema of one schema transaction, as the equal records of its parts. */
    record SchemaState(Basis basis, Instant transactionTime, Origin origin, String principal, Optional<String> onBehalfOf,
            Optional<String> reason, List<EntityType> entityTypes, List<Trait> traits,
            List<RelationshipType> relationshipTypes, List<Origin> origins) {
    }

    /**
     * Everything that can be seen of a store.
     *
     * @param latest       the latest basis
     * @param transactions the data transactions
     * @param purges       the purges
     * @param declaredRules the rules declared without code
     * @param schemaHistory the schema transactions with their schemas
     * @param keyed        what committing each transaction with an idempotency key again gives
     * @param views        every answer of the views, by what was asked
     */
    record Observation(Basis latest, List<TransactionRecord> transactions, List<PurgeRecord> purges,
            List<RuleDescriptor> declaredRules, List<SchemaState> schemaHistory, List<CommitResult> keyed,
            Map<String, Object> views) {
    }

    private StoreEquivalence() {
    }

    /**
     * Observes a store.
     *
     * @param store          the store
     * @param ids            the IDs of the entities and relationships to read; unknown IDs read as absent
     * @param effectiveTimes the effective times of the views
     * @param keyed          the transactions with an idempotency key that were committed; they are committed again, which
     *                       records nothing
     * @return the observation
     */
    static Observation observe(Store store, List<Id> ids, List<Instant> effectiveTimes, List<Transaction> keyed) {
        return observe(store, ids, effectiveTimes, keyed, true);
    }

    /**
     * Observes a store, as {@link #observe(Store, List, List, List)} does, but with {@code everyBasis} false only at
     * the first, the middle and the last two bases, and at the transaction times of those: for stores with hundreds of
     * transactions, where every basis would be a lot to compare.
     */
    static Observation observeSampled(Store store, List<Id> ids, List<Instant> effectiveTimes,
            List<Transaction> keyed) {
        return observe(store, ids, effectiveTimes, keyed, false);
    }

    private static Observation observe(Store store, List<Id> ids, List<Instant> effectiveTimes,
            List<Transaction> keyed, boolean everyBasis) {
        Basis latest = store.latestBasis();
        List<SchemaState> schemaHistory = new ArrayList<>();
        for (SchemaTransaction transaction : store.schema().history()) {
            Schema schema = transaction.schema();
            schemaHistory.add(new SchemaState(transaction.basis(), transaction.transactionTime(), transaction.origin(),
                    transaction.principal(), transaction.onBehalfOf(), transaction.reason(), schema.entityTypes(),
                    schema.traits(), schema.relationshipTypes(), schema.origins()));
        }
        List<CommitResult> keyedResults = keyed.stream().map(store::commit).toList();
        Set<Long> bases = new TreeSet<>();
        for (long basis = 0; basis <= latest.value(); basis++) {
            if (everyBasis || basis == 0 || basis == latest.value() / 2 || basis >= latest.value() - 1) {
                bases.add(basis);
            }
        }
        List<Instant> knownTimes = new ArrayList<>();
        store.transactions().stream().filter(record -> bases.contains(record.basis().value()))
                .forEach(record -> knownTimes.add(record.transactionTime()));
        store.purges().stream().filter(record -> bases.contains(record.basis().value()))
                .forEach(record -> knownTimes.add(record.transactionTime()));
        store.schema().history().stream().filter(transaction -> bases.contains(transaction.basis().value()))
                .forEach(transaction -> knownTimes.add(transaction.transactionTime()));
        Map<String, Object> views = new LinkedHashMap<>();
        for (long basis : bases) {
            for (Instant effective : effectiveTimes) {
                observe(views, "basis " + basis + " effective " + effective,
                        store.view(Coordinates.effectiveAt(effective).knownAt(new Basis(basis))), ids);
            }
        }
        for (Instant known : new TreeSet<>(knownTimes)) {
            for (Instant effective : effectiveTimes) {
                observe(views, "known " + known + " effective " + effective,
                        store.view(Coordinates.effectiveAt(effective).knownAt(known)), ids);
            }
        }
        observe(views, "latest", store.view(Coordinates.effectiveAt(effectiveTimes.getFirst())), ids);
        return new Observation(latest, store.transactions(), store.purges(), store.declaredRules(), schemaHistory,
                keyedResults, views);
    }

    private static void observe(Map<String, Object> views, String where, View view, List<Id> ids) {
        views.put(where + " basis", view.basis());
        for (EntityType type : view.schema().entityTypes()) {
            views.put(where + " entities " + type.name(), view.entities(type.name()));
        }
        for (Id id : ids) {
            switch (id) {
                case EntityId entity -> {
                    views.put(where + " entity " + entity, view.entity(entity));
                    views.put(where + " timeline " + entity, view.timeline(entity));
                    for (RelationshipType type : view.schema().relationshipTypes()) {
                        views.put(where + " navigate " + entity + " " + type.name(),
                                view.navigate(entity, type.name()));
                        views.put(where + " navigate " + entity + " " + type.inverseName(),
                                view.navigate(entity, type.inverseName()));
                    }
                }
                case RelationshipId relationship -> {
                    views.put(where + " relationship " + relationship, view.relationship(relationship));
                    views.put(where + " relationship timeline " + relationship,
                            view.relationshipTimeline(relationship));
                }
            }
        }
    }

    /** Asserts that two observations are equal, part by part, so that a difference says which part differs. */
    static void assertEquivalent(Observation expected, Observation actual) {
        assertThat(actual.latest()).as("latest basis").isEqualTo(expected.latest());
        assertThat(actual.schemaHistory()).as("schema history").isEqualTo(expected.schemaHistory());
        assertThat(actual.transactions()).as("transaction records").isEqualTo(expected.transactions());
        assertThat(actual.purges()).as("purge records").isEqualTo(expected.purges());
        assertThat(actual.declaredRules()).as("declared rules").isEqualTo(expected.declaredRules());
        assertThat(actual.keyed()).as("idempotency keys").isEqualTo(expected.keyed());
        assertThat(actual.views()).as("views").isEqualTo(expected.views());
        assertThat(actual.views()).as("the views asked").isNotEmpty();
    }
}
