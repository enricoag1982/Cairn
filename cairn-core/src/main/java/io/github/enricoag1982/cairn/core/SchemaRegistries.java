package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;
import java.util.Collection;

/**
 * Creates schema registries (capability {@code schema-evolution}).
 */
public final class SchemaRegistries {

    private SchemaRegistries() {
    }

    /**
     * Creates an empty in-memory schema registry without available rules that takes transaction times from the
     * system clock.
     *
     * @return the registry
     */
    public static SchemaRegistry inMemory() {
        return inMemory(InstantSource.system());
    }

    /**
     * Creates an empty in-memory schema registry without available rules.
     *
     * @param clock the source of transaction times
     * @return the registry
     */
    public static SchemaRegistry inMemory(InstantSource clock) {
        return new InMemorySchemaRegistry(clock);
    }

    /**
     * Creates an empty in-memory schema registry with available rules, which entity types and relationship types
     * may name (capability {@code type-model}).
     *
     * @param clock the source of transaction times
     * @param rules the rules that are available, listed by {@link SchemaRegistry#availableRules()}
     * @return the registry
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references (lowercase
     *                                  ASCII letters, digits and hyphens, starting with a letter, at most 128
     *                                  characters; a version of at least 1), or if two rules have the same name and
     *                                  version
     */
    public static SchemaRegistry inMemory(InstantSource clock, Collection<RuleDescriptor> rules) {
        return new InMemorySchemaRegistry(clock, rules);
    }
}
