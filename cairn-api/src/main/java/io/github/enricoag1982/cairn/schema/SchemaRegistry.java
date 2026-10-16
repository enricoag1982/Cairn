package io.github.enricoag1982.cairn.schema;

import io.github.enricoag1982.cairn.data.Clearance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registers schema definitions and keeps the history of the stored schema in transaction time
 * (capability {@code schema-evolution}). Registrations are applied one at a time; reads never wait.
 *
 * <p>A registry is created with the rules that are available to it (capability {@code type-model}); types may name
 * only those. Types may also declare {@linkplain Check checks} (capability {@code checks}), which the registry compiles
 * with an {@linkplain io.github.enricoag1982.cairn.expressions.ExpressionLanguage expression language} when it
 * validates, plans or registers a definition, and so it does the expressions of derived attributes (capability
 * {@code derived-attributes}). A registry that has none rejects every definition that declares checks or derived
 * attributes, with the problem {@code EXPRESSIONS_UNAVAILABLE}.
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
     * Registers the complete set of types of an origin for a caller whose clearance limits what it may change (capability
     * {@code attribute-access}, ADR-0036): as {@link #register(Origin, SchemaDefinition, Submission)} does, but a
     * registration that the caller's clearance does not allow is rejected with a problem of the code
     * {@code ACCESS_DENIED}, and nothing changes. It does not allow a registration that adds, changes or removes the
     * classification of an attribute, where the classification before or after is one that the clearance does not cover,
     * nor one that adds a check or a derived attribute, or changes the expression of one, that reads an attribute whose
     * classification the clearance does not cover: otherwise the caller could declassify an attribute and read it, or
     * probe it with a check. The problem is located at the attribute, the check or the derived attribute, as {@code
     * Account.balance} or {@code Account.balance-above-1000}. The check and the registration are one step under the
     * registry's lock, so no other registration comes between them.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @param submission who submits the registration, for whom and why
     * @param clearance  the clearance of the caller
     * @return the outcome
     * @throws IllegalStateException if the registry is the registry of a store, and a rule of that store calls this
     *                               while the store runs rules: a rule must not write
     */
    RegistrationResult register(Origin origin, SchemaDefinition definition, Submission submission,
            Clearance clearance);

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
     * Finds out what {@link #register(Origin, SchemaDefinition, Submission, Clearance)} would do with the same
     * arguments, without doing it: the plan of a registration for a caller whose clearance limits what it may change.
     * A change that the clearance does not allow is a problem of the code {@code ACCESS_DENIED}, and the outcome is a
     * rejection.
     *
     * @param origin     the origin, with its version
     * @param definition all types of the origin
     * @param clearance  the clearance of the caller
     * @return the plan
     */
    SchemaPlan plan(Origin origin, SchemaDefinition definition, Clearance clearance);

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
