package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.data.InMemoryStore;
import io.github.enricoag1982.cairn.data.Store;
import java.util.Objects;

/**
 * Creates stores (capabilities {@code transactions}, {@code entity-versions}, {@code views} and {@code entity-rules}).
 */
public final class Stores {

    private Stores() {
    }

    /**
     * Creates an empty in-memory store with the default {@linkplain StoreSettings settings}: the system clock, UUIDv7
     * IDs and no rules.
     *
     * @return the store
     */
    public static Store inMemory() {
        return inMemory(StoreSettings.defaults());
    }

    /**
     * Creates an empty in-memory store. The store's schema registry shares its sequence of bases and transaction times
     * with its data transactions. The descriptions of the settings' rules, with the rules the settings declare, are the
     * rules available to the registry; the store runs the rules at commit (capability {@code entity-rules}).
     *
     * @param settings the clock, ID generator and rules; the store works on a copy of the generator
     * @return the store
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references (requirement
     *                                  "Rule references" of the {@code type-model} spec), or if two rules, whether
     *                                  they have code or are declared, have the same name and version
     * @throws NullPointerException     if a rule has no descriptor
     */
    public static Store inMemory(StoreSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new InMemoryStore(settings.clock(), settings.idGenerator().copy(), settings.rules(),
                settings.availableRules());
    }
}
