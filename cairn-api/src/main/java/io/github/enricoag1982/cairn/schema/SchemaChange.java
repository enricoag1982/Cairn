package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * One difference between the stored definition of an origin and a candidate, classified (capability
 * {@code schema-evolution}).
 *
 * @param path        the element concerned: a type name, or a type name and an attribute, inverse name or rule name
 *                    joined by a dot
 * @param description what changed
 * @param compatible  {@code true} if existing data and users can follow the change; a breaking change is rejected
 * @param hint        how to avoid a breaking change, if there is advice
 */
public record SchemaChange(String path, String description, boolean compatible, Optional<String> hint) {

    /**
     * Rejects null components.
     */
    public SchemaChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(hint, "hint");
    }

    @Override
    public String toString() {
        return (compatible ? "compatible" : "breaking") + " at " + path + ": " + description;
    }
}
