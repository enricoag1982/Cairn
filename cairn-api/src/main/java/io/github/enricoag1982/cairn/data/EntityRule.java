package io.github.enricoag1982.cairn.data;

/**
 * A {@linkplain Rule rule} for an entity type (ADR-0018, capability {@code entity-rules}). It checks one version of an
 * entity and reports what is wrong through a {@link RuleContext}.
 *
 * <p>The store runs the rule when a transaction would produce a version of an entity whose type, or one of whose
 * supertypes, names the rule, after the declarative constraints of the schema have passed for that entity. A rule that
 * is for an entity type must be an {@code EntityRule}.
 */
public interface EntityRule extends Rule {

    /**
     * Checks one version of an entity, as the transaction would produce it.
     *
     * <p>The rule reports every problem through {@code context}, and codes that its {@linkplain #descriptor()
     * descriptor} declares only. It does not return a result, and it must not keep {@code context} or any view of it
     * after it returns. An exception that the rule throws rejects the transaction with a problem that names the rule.
     *
     * @param version the version: the entity's ID and type, its values and its effective period
     * @param context what the rule reads and reports through
     */
    void check(EntityState version, RuleContext context);
}
