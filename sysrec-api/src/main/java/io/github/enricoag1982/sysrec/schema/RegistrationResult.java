package io.github.enricoag1982.sysrec.schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link SchemaRegistry#register(Origin, SchemaDefinition)} (capability {@code schema-evolution}).
 */
public sealed interface RegistrationResult {

    /**
     * The registration changed the schema and was recorded as a schema transaction.
     *
     * @param transactionTime when the transaction was recorded
     * @param basis           the basis the transaction produced
     * @param schema          the schema after the transaction, with the identifier of every type and attribute
     */
    record Applied(Instant transactionTime, Basis basis, Schema schema) implements RegistrationResult {
        /**
         * Rejects null components.
         */
        public Applied {
            Objects.requireNonNull(transactionTime, "transactionTime");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * The registration did not change the schema, and no transaction was recorded.
     *
     * @param schema the stored schema
     */
    record Unchanged(Schema schema) implements RegistrationResult {
        /**
         * Rejects a null schema.
         */
        public Unchanged {
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * The registration was rejected, and the stored schema is unchanged.
     *
     * @param problems every reason for the rejection
     */
    record Rejected(List<Problem> problems) implements RegistrationResult {
        /**
         * Copies the problem list.
         */
        public Rejected {
            problems = List.copyOf(problems);
        }
    }
}
