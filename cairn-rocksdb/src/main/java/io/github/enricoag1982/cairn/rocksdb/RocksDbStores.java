package io.github.enricoag1982.cairn.rocksdb;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.kv.KeyValueStores;
import io.github.enricoag1982.cairn.data.DurableStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens stores kept in RocksDB (ADR-0028, capability {@code durability}).
 *
 * <p>A RocksDB store keeps the entities and relationships of the engine's ledger, and what a store keeps beside them (the
 * schema and its history, the transaction records, the receipts of idempotency keys, the purge records and the latest
 * basis), as keys in one RocksDB database. Each commit, schema registration and purge is one write batch, written
 * synchronously to RocksDB's write-ahead log before the store publishes its basis, so that a crash loses no committed
 * transaction and leaves nothing of one that did not commit. RocksDB replays its log when it opens, and the store then
 * reads its metadata; no transaction is replayed. A purge deletes the keys of what it removes in one batch, and then
 * flushes and compacts the database, with the write-ahead log not archived, and opens it again so that its MANIFEST,
 * which names the smallest and the largest key of every file, no longer names the keys that were deleted, so that no file
 * of the store keeps the purged data, not an ID or the value of a unique attribute, when the purge returns. A purge that
 * stopped before that, in a crash, is finished when the store is opened. Opening a store takes longer the more data it
 * holds, because the store reads its metadata into memory and runs its rules once over every version they name to learn
 * what they read.
 *
 * <p>The store lives in a directory, which holds the file {@code lock} that stores in directories take, so that one open
 * store holds a directory at a time, in any process, and the subdirectory {@code rocksdb}, which holds the database and
 * its own lock. The library bundles the native library of RocksDB for the common platforms and loads it when the first
 * store is opened; Java 24 and later warn about that unless native access is enabled: run applications with
 * {@code --enable-native-access=rocksdbjni} on the module path, or {@code ALL-UNNAMED} on the class path.
 */
public final class RocksDbStores {

    /** The subdirectory of the store's directory that holds the RocksDB database. */
    static final String DATABASE = "rocksdb";

    private RocksDbStores() {
    }

    /**
     * Opens the store kept in a directory, which is created if it does not exist (capability {@code durability}). A new or
     * empty directory gives an empty store. A directory that a RocksDB store wrote gives that store's state: the schema and
     * its history, every version, the transaction records, the receipts of idempotency keys and the purge records, at the
     * same bases and transaction times. Opening applies what was recorded; it does not validate, run rules, or use the
     * settings' clock or ID generator, though the sequence of transaction times goes on after the last recorded one and a
     * generated ID is never one that the store has. A purge that was written and did not finish compacting the files, because
     * the process stopped, is finished first: no file of the store keeps what it removed once this returns. The store then
     * runs its rules once over every version their types name them for, only to learn what they read (ADR-0025).
     *
     * <p>Every commit, schema registration and purge is on disk before it returns. The store holds the directory until it
     * is {@linkplain DurableStore#close() closed}; no other store can be opened on it meanwhile, in this process or
     * another. The settings are those of {@code Stores.inMemory}: the descriptions of the settings' rules, with the rules
     * they declare, are the rules available to the schema, and a rule that the settings declare without code, or do not
     * name, is not run: a write that needs it is rejected, as with an in-memory store, whatever the store recorded.
     *
     * @param directory the directory, which is created if it does not exist
     * @param settings  the clock, ID generator and rules; the store works on a copy of the generator
     * @return the open store, which the caller closes
     * @throws UncheckedIOException     if reading or writing the directory or the database fails
     * @throws IllegalStateException    if another open store holds the directory, if the database was written in a format
     *                                  version that this version of Cairn does not know, which the message names, if a
     *                                  stored record is damaged, or if the settings' ID generator does not
     *                                  {@linkplain io.github.enricoag1982.cairn.data.IdGenerator#accepts accept} the ID of
     *                                  an entity or a relationship that the store holds
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two rules,
     *                                  whether they have code or are declared, have the same name and version
     * @throws NullPointerException     if a rule has no descriptor
     */
    public static DurableStore open(Path directory, StoreSettings settings) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(settings, "settings");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Creating the directory " + directory + " failed.", e);
        }
        Runnable release = KeyValueStores.lockDirectory(directory);
        RocksDbKeyValueStore keys;
        try {
            keys = RocksDbKeyValueStore.open(directory.resolve(DATABASE), release);
        } catch (RuntimeException | Error e) {
            release.run();
            throw e;
        }
        try {
            return KeyValueStores.openDurable(keys, settings);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Opening the RocksDB store in " + directory + " failed: " + e.getMessage(),
                    e);
        }
    }
}
