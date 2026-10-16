package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * The names of the attributes that the check reads, which the engine compares with a clearance (ADR-0036): those
     * that the compiled check says it reads, or every attribute of the type if the language cannot tell. A check that is
     * not compiled reads none.
     *
     * @param attributes the attributes of the type the check applies to
     * @return the names, as the attributes are named
     */
    public Set<String> reads(Collection<Attribute> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        if (compiled.isEmpty()) {
            return Set.of();
        }
        return compiled.get().attributesRead()
                .orElseGet(() -> attributes.stream().map(Attribute::name).collect(Collectors.toUnmodifiableSet()));
    }
}
