package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.schema.Attribute;
import java.util.Objects;
import java.util.Optional;

/**
 * A derived attribute of an entity type or relationship type of a registered schema, as the schema keeps it for running
 * (design D3 and D4 of {@code add-derived-attributes}): the attribute with its expression, and the derivation compiled
 * for the type it belongs to, against the attributes of the type that are not derived. A derivation that is not compiled
 * is one that the registry had no expression language for, or that the language could not compile when the schema was
 * restored from a log or a snapshot file.
 *
 * @param attribute the derived attribute, as the type has it
 * @param compiled  the derivation compiled for the type; empty if it is not compiled
 * @param failure   why the derivation is not compiled although the registry has an expression language, as the language
 *                  said; empty if it is compiled, or if the registry has no expression language
 */
public record AppliedDerivation(Attribute attribute, Optional<CompiledDerivation> compiled, Optional<String> failure) {

    /**
     * Rejects null components.
     */
    public AppliedDerivation {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(compiled, "compiled");
        Objects.requireNonNull(failure, "failure");
    }

    /**
     * Tells whether the registry had no expression language to compile the derivation with.
     *
     * @return {@code true} if the derivation is neither compiled nor failed
     */
    public boolean unavailable() {
        return compiled.isEmpty() && failure.isEmpty();
    }
}
