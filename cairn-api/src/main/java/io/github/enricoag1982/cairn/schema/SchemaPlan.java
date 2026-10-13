package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;

/**
 * What {@link SchemaRegistry#register(Origin, SchemaDefinition)} would do with a registration, found without doing it
 * (capability {@code schema-evolution}).
 *
 * @param outcome  whether the registration would be applied, leave the schema unchanged or be rejected
 * @param changes  every difference from the stored definition of the origin, compatible or breaking; empty if the
 *                 definition is invalid, because an invalid definition cannot be compared
 * @param problems every problem that registering would report; empty unless the outcome is {@link Outcome#REJECT}
 */
public record SchemaPlan(Outcome outcome, List<SchemaChange> changes, List<Problem> problems) {

    /** What a registration would do. */
    public enum Outcome {
        /** The registration would change the schema and be recorded as a schema transaction. */
        APPLY,
        /** The registration would not change the schema, and no transaction would be recorded. */
        UNCHANGED,
        /** The registration would be rejected, and the stored schema would stay as it is. */
        REJECT
    }

    /**
     * Rejects a null outcome and copies the lists.
     */
    public SchemaPlan {
        Objects.requireNonNull(outcome, "outcome");
        changes = List.copyOf(changes);
        problems = List.copyOf(problems);
    }
}
