package io.github.enricoag1982.sysrec.core;

import io.github.enricoag1982.sysrec.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.sysrec.schema.SchemaRegistry;
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
