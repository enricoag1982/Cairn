package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * A check that applies to an entity type or relationship type, with the type that declares it (capability
 * {@code checks}). A check that a type declares applies to its subtypes too.
 *
 * @param check      the check's name, expression and message
 * @param declaredBy the name of the entity type or relationship type that declares the check
 */
public record EffectiveCheck(Check check, String declaredBy) {

    /**
     * Rejects null components.
     */
    public EffectiveCheck {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(declaredBy, "declaredBy");
    }
}
