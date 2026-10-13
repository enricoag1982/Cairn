package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.data.InMemoryStore;
import io.github.enricoag1982.cairn.data.Store;
import java.util.Objects;

/**
 * Creates stores (capabilities {@code transactions}, {@code entity-versions} and {@code views}).
 */
public final class Stores {

    private Stores() {
    }

    /**
     * Creates an empty in-memory store with the default {@linkplain StoreSettings settings}: the system clock, UUIDv7
     * IDs and no available rules.
     *
     * @return the store
     */
    public static Store inMemory() {
        return inMemory(StoreSettings.defaults());
    }

    /**
     * Creates an empty in-memory store. The store's schema registry shares its sequence of bases and transaction times
     * with its data transactions.
     *
     * @param settings the clock, ID generator and available rules; the store works on a copy of the generator
     * @return the store
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references (lowercase
     *                                  ASCII letters, digits and hyphens, starting with a letter, at most 128
     *                                  characters; a version of at least 1), or if two rules have the same name and
     *                                  version
     */
    public static Store inMemory(StoreSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new InMemoryStore(settings.clock(), settings.idGenerator().copy(), settings.availableRules());
    }
}
