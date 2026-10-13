package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;

/**
 * Creates schema registries (capability {@code schema-evolution}).
 */
public final class SchemaRegistries {

    private SchemaRegistries() {
    }

    /**
     * Creates an empty in-memory schema registry that takes transaction times from the system clock.
     *
     * @return the registry
     */
    public static SchemaRegistry inMemory() {
        return inMemory(InstantSource.system());
    }

    /**
     * Creates an empty in-memory schema registry.
     *
     * @param clock the source of transaction times
     * @return the registry
     */
    public static SchemaRegistry inMemory(InstantSource clock) {
        return new InMemorySchemaRegistry(clock);
    }
}
