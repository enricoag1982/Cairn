package io.github.enricoag1982.cairn.data;

/**
 * A {@linkplain Rule rule} for an entity type (ADR-0018, capability {@code entity-rules}). It checks one version of an
 * entity and reports what is wrong through a {@link RuleContext}.
 *
 * <p>The store runs the rule for every version of an entity whose values a transaction changes, when the entity's type,
 * or one of its supertypes, names the rule, after the declarative constraints of the schema have passed for that
 * entity. A write that changes no values runs no rules for the entity: not one that sets the values an entity already
 * has, not one that splits a version, and not one that only ends the entity. Parts of the entity that the transaction
 * leaves as they are are not checked again, unless the transaction changes what the rule read when it last checked
 * them: then the store runs the rule again for those versions (a re-check), against the state the transaction would
 * produce. It does so whether or not the transaction writes the entity, and a write that only ends something, such as
 * a relationship that the rule navigated, can cause one. A problem from a re-check is about the entity that is checked
 * again, by its ID if the transaction does not name it, and its hint says that the transaction changed what the rule
 * read. A rule that is for an entity type must be an {@code EntityRule}; a rule for a relationship type is a
 * {@link RelationshipRule}.
 *
 * <p>As for every {@link Rule}, the types whose writes can break an entity rule need not name it: the store finds those
 * writes from what the rule read through its {@link RuleContext}.
 *
 * <p>Because the store checks only what changed, a rule's verdict must hold at each instant of the version's effective
 * period, and must not depend on where that period starts or ends: the store may check a part of an entity's timeline
 * that begins or ends where a write happened to begin or end, and it will not check the rest unless what the rule read
 * there changes. A rule that needs "now" uses the version's {@linkplain EntityState#recordedAt() transaction time},
 * which is the time of the transaction that records it (in a dry run, the time a commit would get), and never a clock.
 *
 * <p>A rule only reads. While rules run, the store refuses to commit, run dry or register a schema, and throws an
 * {@link IllegalStateException} to a rule that tries, for example through a reference to its store. A rule that catches
 * that exception and returns has still failed: the store notices the refused write, and rejects the transaction with a
 * problem that names the rule.
 *
 * <p>A rule must not start a write on another thread and wait for it. The store holds its writer lock while rules run,
 * so the other thread waits for the lock and the rule waits for the other thread: that deadlocks, and the store stops
 * committing. A write on another thread that the rule does not wait for is not refused, but it only runs after the
 * commit that runs the rule has ended.
 */
public interface EntityRule extends Rule {

    /**
     * Checks one version of an entity, as the transaction would produce it.
     *
     * <p>The rule reports every problem through {@code context}, with codes that its {@linkplain #descriptor()
     * descriptor} declares only and attributes that the entity's type has only. It does not return a result, and it
     * must not keep {@code context} or any view of it after it returns. It must not write: a commit, a dry run or a
     * schema registration in the store while the rule runs throws an {@link IllegalStateException}, and the rule is
     * reported as failed even if it catches that exception.
     *
     * <p>An exception or an error that the rule throws rejects the transaction with a problem that names the rule, and
     * so does a report that breaks the rules above. A {@link VirtualMachineError}, such as {@link OutOfMemoryError} or
     * {@link StackOverflowError}, is not caught: it leaves the store's {@code commit} or {@code dryRun} as it is, and
     * the store is unchanged.
     *
     * @param version the version: the entity's ID and type, its values and its effective period
     * @param context what the rule reads and reports through
     */
    void check(EntityState version, RuleContext context);
}
