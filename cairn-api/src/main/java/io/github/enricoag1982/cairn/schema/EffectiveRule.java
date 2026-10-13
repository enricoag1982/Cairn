package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * A rule that applies to an entity type or relationship type, with the type that names it (capability
 * {@code type-model}). A rule that a type names applies to its subtypes too.
 *
 * @param rule       the rule's name and version
 * @param declaredBy the name of the entity type or relationship type that names the rule
 */
public record EffectiveRule(RuleReference rule, String declaredBy) {

    /**
     * Rejects null components.
     */
    public EffectiveRule {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(declaredBy, "declaredBy");
    }
}
