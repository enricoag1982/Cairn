package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.data.DirectoryStore;
import io.github.enricoag1982.cairn.core.data.InMemoryStore;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.Store;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Creates stores (capabilities {@code transactions}, {@code entity-versions}, {@code views}, {@code entity-rules} and
 * {@code durability}).
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
     * rules available to the registry; the store runs the rules at commit, and again when a transaction changes what
     * they read (capability {@code entity-rules}, ADR-0025). While it runs them, the store refuses writes: a rule that
     * commits, runs dry or registers a schema in its store gets an {@code IllegalStateException}, and its transaction
     * is rejected.
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
                settings.declaredRules());
    }

    /**
     * Opens a durable store on a directory with the default {@linkplain StoreSettings settings}: the system clock,
     * UUIDv7 IDs and no rules.
     *
     * @param directory the directory, which is created if it does not exist
     * @return the open store, which the caller closes
     * @throws java.io.UncheckedIOException if reading or writing the directory fails
     * @throws IllegalStateException        if another open store holds the directory, if the directory holds a RocksDB
     *                                      store, if a file in it is damaged anywhere but at the end of the last log
     *                                      segment, or if a file was written in a format version that this version does
     *                                      not know; the message names the file
     * @see #durable(Path, StoreSettings)
     */
    public static DurableStore durable(Path directory) {
        return durable(directory, StoreSettings.defaults());
    }

    /**
     * Opens a durable store on a directory (capability {@code durability}, ADR-0022). A new or empty directory gives an
     * empty store. A directory that a store wrote gives that store's state, recovered from the newest valid snapshot file
     * and the log after it: the schema and its history, every version, the transaction records and the receipts of
     * idempotency keys, at the same bases and transaction times. Recovery applies what was recorded; it does not validate,
     * run rules, or use the settings' clock or ID generator. The sequence of transaction times continues after the last
     * recorded one even if the clock has gone back, and a generated ID is never one that the store has.
     *
     * <p>The store remembers what its rules read when they last checked each version, so that it can run them again
     * when a transaction changes that, and it keeps that in memory, not in its files. So after recovery, before it
     * returns, the store runs each rule it has code for once over every version of every entity and relationship whose
     * type names it, without judging the results, to learn what they read (ADR-0025). Opening takes longer the more data
     * the store holds. What the rules report, throw or try to write while the store learns does not stop it from
     * opening, unless the virtual machine runs out of memory or fails.
     *
     * <p>Every commit and schema registration is on disk before it returns. The store holds the directory until it is
     * {@linkplain DurableStore#close() closed}; no other store can be opened on it meanwhile, in this process or another.
     * The settings are those of {@link #inMemory(StoreSettings)}: the descriptions of the settings' rules, with the rules
     * they declare, are the rules available to the schema, and a rule that the settings declare without code, or do not
     * name, is not run: a write that needs it is rejected, as with an in-memory store, whatever the store recorded.
     *
     * @param directory the directory, which is created if it does not exist
     * @param settings  the clock, ID generator and rules; the store works on a copy of the generator
     * @return the open store, which the caller closes
     * @throws java.io.UncheckedIOException if reading or writing the directory fails
     * @throws IllegalStateException        if another open store holds the directory, if the directory holds a RocksDB
     *                                      store (a subdirectory {@code rocksdb}, written by {@code RocksDbStores} of the
     *                                      module {@code cairn-rocksdb}), which is left as it is and is opened with that
     *                                      class, if a file in it is damaged anywhere but at the end of the last log
     *                                      segment, if a file was written in a format version that this version does not
     *                                      know, or if the settings' ID
     *                                      generator does not {@linkplain io.github.enricoag1982.cairn.data.IdGenerator#accepts
     *                                      accept} the ID of an entity or a relationship that the store holds, as the
     *                                      generator of another store; the message names the file and, for damage, the
     *                                      byte position, or one ID that is not accepted
     * @throws IllegalArgumentException     if a rule's name or version breaks the rules for rule references, or if two
     *                                      rules, whether they have code or are declared, have the same name and version
     * @throws NullPointerException         if a rule has no descriptor
     */
    public static DurableStore durable(Path directory, StoreSettings settings) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(settings, "settings");
        return DirectoryStore.open(directory, settings.clock(), settings.idGenerator().copy(), settings.rules(),
                settings.declaredRules());
    }
}
