package io.github.enricoag1982.cairn.rocksdb;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.SchemaChange;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.Trait;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Changes in a form that two stores, or a store before it is closed and after it is opened again, can be compared in: a
 * schema change holds a schema that two stores do not share and that has no equality of its own, so it is replaced by the
 * parts of the schema. A copy of the fixture of the same name in the tests of {@code cairn-core}, which this module's
 * tests cannot reach.
 */
final class ComparableChanges {

    /** A schema change by its values: the transaction, and the types and origins of the schema that it produced. */
    record SchemaState(Basis basis, Instant transactionTime, Origin origin, String principal,
            Optional<String> onBehalfOf, Optional<String> reason, List<EntityType> entityTypes, List<Trait> traits,
            List<RelationshipType> relationshipTypes, List<Origin> origins) {
    }

    private ComparableChanges() {
    }

    /**
     * Turns changes into values that are equal if the changes say the same.
     *
     * @param changes the changes
     * @return the changes, with each schema change replaced by its {@link SchemaState}
     */
    static List<Object> of(List<Change> changes) {
        return changes.stream().map(change -> change instanceof SchemaChange schemaChange
                ? (Object) state(schemaChange.registration()) : change).toList();
    }

    private static SchemaState state(SchemaTransaction transaction) {
        return new SchemaState(transaction.basis(), transaction.transactionTime(), transaction.origin(),
                transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                transaction.schema().entityTypes(), transaction.schema().traits(),
                transaction.schema().relationshipTypes(), transaction.schema().origins());
    }
}
