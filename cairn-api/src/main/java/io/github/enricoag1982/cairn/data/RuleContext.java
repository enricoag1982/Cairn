package io.github.enricoag1982.cairn.data;

import java.time.Instant;

/**
 * What a {@linkplain Rule rule} reads and reports through while it checks a version of an entity or of a relationship
 * (ADR-0018, capability {@code entity-rules}). It can read, and it cannot write: there is no way to change data from
 * here.
 *
 * <p>The views give the state that the transaction would produce, with the rest of the store as committed. They are
 * valid only while the rule runs. In particular their {@linkplain View#basis() basis} is the one the transaction would
 * get; in a dry run, or if the transaction is rejected, that basis later names another commit, so a rule must not keep
 * it or a view.
 *
 * <p>The store records what a rule reads through its context (ADR-0025), and runs the rule again when a transaction
 * changes it. Every read through {@link #view()} and {@link #viewAt(Instant)} counts: an entity or a relationship, with
 * every state that a read returns, what navigation or a traversal finds from an entity by a relationship name, and the
 * entities of an entity type that a read, the start of a query or {@linkplain View#entity(UniqueKey) finding an entity by
 * a unique key} lists, each at the effective time of the view, or across effective time for timelines. A change to any
 * of these at an effective time that the rule read, in the values or in the existence of what was read, runs the rule
 * again for the version whose check read it. So a rule has to read only through its context: what it reads anywhere
 * else, the store cannot see.
 *
 * <p>Prefer navigating from what the rule checks to listing the entities of a type or reading timelines. A rule that
 * lists a type, or reads a timeline, is reached by every change to any of those entities, at any time, and is run
 * again for all of them; a rule that navigates from what it checks is reached only by changes to what it reaches. A
 * query starts by listing the entities of its start type, so a rule that runs a query is reached by any change to an
 * entity of that type at the instants it read, whatever the query goes on to select. A query that starts from given
 * entities ({@link io.github.enricoag1982.cairn.query.Query.Builder#ids(EntityId...) ids}) reads those entities and
 * not the type, and a rule that finds an entity by a unique key reads the entities of the key's type.
 *
 * <p>A context belongs to one call of a rule and is not safe for use by several threads. Reporting after the call has
 * returned throws {@link IllegalStateException}, and reads through a view after the call has returned are not recorded.
 */
public interface RuleContext {

    /**
     * A view of the state the transaction would produce, effective at the start of the checked version's effective
     * period. Other data may change inside the period: a rule whose verdict depends on it reads the state at the other
     * instants with {@link #viewAt(Instant)}.
     *
     * @return the view, read-only
     */
    View view();

    /**
     * A view of the same state at any other effective time, so that a rule can check every point of the version's
     * period where other data changes. The time need not lie in the version's period.
     *
     * @param effectiveAt the effective time; truncated to microseconds
     * @return the view, read-only
     */
    View viewAt(Instant effectiveAt);

    /**
     * Reports a problem about an attribute of the checked entity or relationship.
     *
     * @param code      one of the codes the rule's descriptor declares, such as {@code invalid-check-digits}; a code
     *                  that it does not declare makes the store reject the transaction with a problem about the rule
     * @param attribute the name, or an alias, of an attribute of the type of the checked entity or relationship, in the
     *                  schema that the transaction would produce; an attribute that the type does not have makes the
     *                  store reject the transaction with a problem about the rule. The problem is located at the
     *                  attribute's name
     * @param message   what is wrong, readable by people and agents
     * @throws IllegalStateException if the rule has returned
     */
    void problem(String code, String attribute, String message);

    /**
     * Reports a problem about the whole checked entity or relationship.
     *
     * @param code    one of the codes the rule's descriptor declares
     * @param message what is wrong, readable by people and agents
     * @throws IllegalStateException if the rule has returned
     */
    void problem(String code, String message);
}
