package io.github.enricoag1982.cairn.schema;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registers schema definitions and keeps the history of the stored schema in transaction time
 * (capability {@code schema-evolution}). Registrations are applied one at a time; reads never wait.
 *
 * <p>A registry is created with the rules that are available to it (capability {@code type-model}); types may name
 * only those.
 */
public interface SchemaRegistry {

    /**
     * Registers the complete set of types of an origin, without saying who submits it: the schema transaction is
     * recorded with the principal {@value Submission#UNKNOWN_PRINCIPAL}. This is
     * {@code register(origin, definition, Submission.unknown())}.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @return the outcome
     * @throws IllegalStateException if the registry is the registry of a store, and a rule of that store calls this
     *                               while the store runs rules: a rule must not write
     * @see #register(Origin, SchemaDefinition, Submission)
     */
    default RegistrationResult register(Origin origin, SchemaDefinition definition) {
        return register(origin, definition, Submission.unknown());
    }

    /**
     * Registers the complete set of types of an origin, for a principal.
     *
     * <p>The definition is validated together with the types of the other origins. A valid definition that changes
     * the stored schema compatibly is applied as a schema transaction, which records the submission's principal, the
     * party it acts for and its reason, and lists them in the {@linkplain #history() history}. A definition equal to
     * the stored one, or an older version of the origin that the stored definition evolved from compatibly, leaves the
     * schema unchanged. Anything else is rejected with every problem found. A registration that is not applied records
     * nothing.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @param submission who submits the registration, for whom and why
     * @return the outcome
     * @throws IllegalStateException if the registry is the registry of a store, and a rule of that store calls this
     *                               while the store runs rules: a rule must not write
     */
    RegistrationResult register(Origin origin, SchemaDefinition definition, Submission submission);

    /**
     * Finds out what {@link #register(Origin, SchemaDefinition, Submission)} would do with the same arguments, without
     * doing it.
     *
     * <p>The plan lists every difference from the stored definition of the origin, each classified as compatible or
     * breaking, and every problem that registering would report. It shares its code with registration, so both agree.
     * Planning records no schema transaction and does not change the stored schema.
     *
     * <p>For an older version of an origin, the differences are those of the reversed comparison: what the stored
     * version adds to the submitted one.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @return the plan
     */
    SchemaPlan plan(Origin origin, SchemaDefinition definition);

    /**
     * Validates a definition as {@link #register(Origin, SchemaDefinition, Submission)} would, without checking compatibility
     * and without changing anything.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @return every problem found; empty if the definition is valid
     */
    List<Problem> validate(Origin origin, SchemaDefinition definition);

    /**
     * Lists the rules that are available to this registry. Types may name these rules, and only these.
     *
     * @return the rules, sorted by name and then by version
     */
    List<RuleDescriptor> availableRules();

    /**
     * Returns the origin and the definition that the stored schema holds for an origin name: those of the latest
     * applied registration of that origin, which is the definition a tool can export as a schema document.
     *
     * <p>A registration that was rejected or left the schema unchanged does not change what is returned; neither does
     * registering an older version of the origin.
     *
     * @param originName the name of the origin, compared as written
     * @return the origin, with the version of the latest applied registration, and its definition; empty if the
     *         origin never registered
     */
    Optional<StoredDefinition> storedDefinition(String originName);

    /**
     * Returns the latest schema.
     *
     * @return the schema after the latest schema transaction, or an empty schema
     */
    Schema current();

    /**
     * Returns the schema as it was at a basis.
     *
     * @param basis the basis
     * @return the schema after the latest schema transaction at or before the basis, or an empty schema
     */
    Schema asOf(Basis basis);

    /**
     * Returns the schema as it was at a transaction time.
     *
     * @param transactionTime the transaction time
     * @return the schema after the latest schema transaction at or before that time, or an empty schema
     */
    Schema asOf(Instant transactionTime);

    /**
     * Lists every applied schema transaction, with the principal, the party acted for and the reason of its
     * registration.
     *
     * @return the transactions, oldest first
     */
    List<SchemaTransaction> history();
}
