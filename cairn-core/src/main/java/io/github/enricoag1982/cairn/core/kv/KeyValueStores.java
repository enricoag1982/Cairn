package io.github.enricoag1982.cairn.core.kv;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.data.PersistentStore;
import io.github.enricoag1982.cairn.core.storage.DirectoryLock;
import io.github.enricoag1982.cairn.data.DurableStore;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens stores over a {@link PersistentKeyValueStore} (ADR-0028, design D1 of {@code add-rocksdb-store}): the entry point
 * of a backend such as the RocksDB store of {@code cairn-rocksdb}, to which this package is exported and to no other
 * module.
 */
public final class KeyValueStores {

    private KeyValueStores() {
    }

    /**
     * Opens a store over a persistent key-value store, with the behavior of an in-memory store (capability
     * {@code durability}). A key-value store that holds nothing gives an empty store, and it gets the store's format
     * version; one that a store wrote gives that store's state: the schema and its history, every version, the transaction
     * records, the receipts of idempotency keys and the purge records, at the same bases and transaction times, read from
     * the keys that the store wrote beside its entities and relationships. Opening applies what was recorded; it does not
     * validate, run rules, or use the settings' clock or ID generator, though the sequence of transaction times goes on
     * after the last recorded one and a generated ID is never one that the store has. After that the store runs each of
     * its rules once over every version their types name them for, only to learn what they read (ADR-0025), so opening
     * takes longer the more data the store holds.
     *
     * <p>Every commit, schema registration and purge is written as one batch, which {@link PersistentKeyValueStore#apply}
     * makes durable before the store publishes its basis. A purge flushes and compacts the key-value store before it
     * returns, and its batch holds a note that it did not yet, which it deletes afterwards: a key-value store whose purge
     * was stopped before that has the note, and opening it flushes and compacts first, so that no file keeps what the
     * purge removed. {@linkplain DurableStore#writeSnapshot() Writing a snapshot} flushes it, and so does closing the store,
     * unless a write or a purge's compaction failed. The returned store owns the key-value store: closing the store closes
     * it, and so does a failure to open.
     *
     * @param keys     the key-value store, which no one else writes to
     * @param settings the clock, ID generator and rules; the store works on a copy of the generator
     * @return the open store, which the caller closes
     * @throws IllegalStateException    if the key-value store holds keys but no format version, or a format version that
     *                                  this version of Cairn does not know, which the message names, if a stored record is
     *                                  damaged, or if the settings' ID generator does not
     *                                  {@linkplain io.github.enricoag1982.cairn.data.IdGenerator#accepts accept} the ID of
     *                                  an entity or a relationship that the store holds
     * @throws java.io.UncheckedIOException if reading or writing the key-value store fails
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two rules
     *                                  have the same name and version
     * @throws NullPointerException     if a rule has no descriptor
     */
    public static DurableStore openDurable(PersistentKeyValueStore keys, StoreSettings settings) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(settings, "settings");
        return PersistentStore.open(keys, settings.clock(), settings.idGenerator().copy(), settings.rules(),
                settings.declaredRules());
    }

    /**
     * Takes the lock that lets one open store hold a directory: the lock that stores kept in a directory through a commit
     * log take, on the file {@code lock} of the directory, so that a directory is held by one open store at a time, in
     * any process, whichever kind of store it is. A backend calls it before it opens its files, and releases the lock
     * when it is closed.
     *
     * @param directory the directory, which exists
     * @return the action that releases the lock, so that another store can be opened on the directory; calling it again
     *         does nothing
     * @throws IllegalStateException        if another open store, in this JVM or another process, holds the directory
     * @throws java.io.UncheckedIOException if the lock file cannot be opened or locked
     */
    public static Runnable lockDirectory(Path directory) {
        DirectoryLock lock = DirectoryLock.acquire(Objects.requireNonNull(directory, "directory"));
        return lock::close;
    }
}
