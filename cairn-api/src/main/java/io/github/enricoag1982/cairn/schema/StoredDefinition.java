package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * What the stored schema holds for one origin: the latest applied registration of that origin, with its version
 * (capability {@code schema-evolution}).
 *
 * @param origin     the origin, with the version of the latest applied registration
 * @param definition the definition that was registered with it
 */
public record StoredDefinition(Origin origin, SchemaDefinition definition) {

    /**
     * Rejects null components.
     */
    public StoredDefinition {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
    }
}
