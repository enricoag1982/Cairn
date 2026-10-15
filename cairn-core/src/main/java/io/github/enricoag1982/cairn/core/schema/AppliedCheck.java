package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import java.util.Objects;
import java.util.Optional;

/**
 * A check that applies to an entity type or relationship type of a registered schema, as the schema keeps it for
 * running (design D2 and D4 of {@code add-cel-expressions}): the check with the type that declares it, and the check
 * compiled for the type it applies to. A check that is not compiled is one that the registry had no expression language
 * for, or that the language could not compile when the schema was restored from a log or a snapshot file.
 *
 * @param check    the check, with the type that declares it
 * @param compiled the check compiled for the type it applies to; empty if it is not compiled
 * @param failure  why the check is not compiled although the registry has an expression language, as the language said;
 *                 empty if it is compiled, or if the registry has no expression language
 */
public record AppliedCheck(EffectiveCheck check, Optional<CompiledCheck> compiled, Optional<String> failure) {

    /**
     * Rejects null components.
     */
    public AppliedCheck {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(compiled, "compiled");
        Objects.requireNonNull(failure, "failure");
    }

    /**
     * Tells whether the registry had no expression language to compile the check with.
     *
     * @return {@code true} if the check is neither compiled nor failed
     */
    public boolean unavailable() {
        return compiled.isEmpty() && failure.isEmpty();
    }
}
