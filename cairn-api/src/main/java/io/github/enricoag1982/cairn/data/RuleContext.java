package io.github.enricoag1982.cairn.data;

import java.time.Instant;

/**
 * What a {@linkplain Rule rule} reads and reports through while it checks a version (ADR-0018, capability
 * {@code entity-rules}). It can read, and it cannot write: there is no way to change data from here.
 *
 * <p>The views give the state that the transaction would produce, with the rest of the store as committed. They are
 * valid only while the rule runs. In particular their {@linkplain View#basis() basis} is the one the transaction would
 * get; in a dry run, or if the transaction is rejected, that basis later names another commit, so a rule must not keep
 * it or a view.
 *
 * <p>A context belongs to one call of a rule and is not safe for use by several threads. Reporting after the call has
 * returned throws {@link IllegalStateException}.
 */
public interface RuleContext {

    /**
     * A view of the state the transaction would produce, effective at the start of the checked version's effective
     * period.
     *
     * @return the view, read-only
     */
    View view();

    /**
     * A view of the same state at another effective time, so that a rule can check every point of the version's
     * period where other data changes.
     *
     * @param effectiveAt the effective time; truncated to microseconds
     * @return the view, read-only
     */
    View viewAt(Instant effectiveAt);

    /**
     * Reports a problem about an attribute of the checked entity.
     *
     * @param code      one of the codes the rule's descriptor declares, such as {@code invalid-check-digits}; a code
     *                  that it does not declare makes the store reject the transaction with a problem about the rule
     * @param attribute the name of the attribute, as the version reports it
     * @param message   what is wrong, readable by people and agents
     * @throws IllegalStateException if the rule has returned
     */
    void problem(String code, String attribute, String message);

    /**
     * Reports a problem about the whole checked entity.
     *
     * @param code    one of the codes the rule's descriptor declares
     * @param message what is wrong, readable by people and agents
     * @throws IllegalStateException if the rule has returned
     */
    void problem(String code, String message);
}
