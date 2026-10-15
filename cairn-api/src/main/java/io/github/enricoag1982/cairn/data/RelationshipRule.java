package io.github.enricoag1982.cairn.data;

/**
 * A {@linkplain Rule rule} for a relationship type (ADR-0018, capability {@code entity-rules}). It checks one version of
 * a relationship and reports what is wrong through a {@link RuleContext}.
 *
 * <p>It works as an {@link EntityRule} does, for relationships: the store runs the rule for every version of a
 * relationship whose values a transaction changes, when the relationship's type, or one of its supertypes, names the
 * rule, after the declarative constraints of the schema have passed for that relationship, and for the relationships
 * that the transaction relates. A write that changes no values runs no rules for the relationship: not one that sets
 * the values a relationship already has, not one that splits a version, and not one that only ends the relationship. A
 * rule runs again for the versions of a relationship whose values the transaction leaves as they are if the
 * transaction changes what the rule read when it last checked them, as for an {@link EntityRule}. A rule that is for a
 * relationship type must be a {@code RelationshipRule}.
 *
 * <p>The verdict of a rule must hold at each instant of the version's effective period and must not depend on where
 * that period starts or ends, as for an {@code EntityRule}. A rule that needs "now" uses the version's
 * {@linkplain RelationshipState#recordedAt() transaction time}, never a clock. The rule only reads: it must not write,
 * and it must not start a write on another thread and wait for it. See {@link EntityRule} for what the store does when
 * a rule fails, writes or reports wrongly.
 *
 * <p>A rule whose verdict depends on other relationships, such as a sum over all the relationships of one entity,
 * cannot be run only for the version it checks: relationships that start or end inside the version's period change
 * the sum. It reads the state at every instant of the version's period at which those relationships change, through
 * {@link RuleContext#viewAt}. It is named only by the type it checks, not by the types of the relationships it reads:
 * a write that changes one of them, in a period that the rule read, runs the rule again for the version it checks.
 */
public interface RelationshipRule extends Rule {

    /**
     * Checks one version of a relationship, as the transaction would produce it.
     *
     * <p>The rule reports every problem through {@code context}, with codes that its {@linkplain #descriptor()
     * descriptor} declares only and attributes that the relationship's type has only. It does not return a result, and
     * it must not keep {@code context} or any view of it after it returns. It must not write. An exception or an error
     * that the rule throws rejects the transaction with a problem that names the rule, as for an
     * {@link EntityRule#check entity rule}.
     *
     * @param version the version: the relationship's ID and type, its ends, its values and its effective period
     * @param context what the rule reads and reports through
     */
    void check(RelationshipState version, RuleContext context);
}
