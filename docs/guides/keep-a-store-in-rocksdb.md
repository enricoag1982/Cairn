---
last-reviewed: 2026-10-16
owner: ""
---

# Keep a store in RocksDB

How to keep a Cairn store in RocksDB, so that it holds more data than fits in memory and survives a restart or a crash.
A *RocksDB store* keeps the engine's keys ([ADR-0027](../decisions/0027-encode-bitemporal-data-as-keys-over-an-ordered-key-value-interface.md)) and what the store records beside them in one RocksDB database, and writes each commit as one synchronous batch.
Why it works this way is in [ADR-0028](../decisions/0028-keep-a-store-in-rocksdb-with-its-metadata-as-keys-and-its-write-ahead-log-as-the-commit-log.md); what it guarantees is in the `durability` and `purge` specs.
It behaves as a store in memory in every other way: the same scenario tests run against both.

## Add the module

The store is in the module `cairn-rocksdb` (`io.github.enricoag1982.cairn.rocksdb`), which depends on `cairn-core` and on the RocksDB Java library, `org.rocksdb:rocksdbjni`.
That library bundles RocksDB's native library for the common platforms and loads it when the first store is opened.
Java 24 and later warn about that unless native access is enabled: run the application with `--enable-native-access=rocksdbjni` on the module path, or `--enable-native-access=ALL-UNNAMED` on the class path.
Applications that do not use this module load no native library.

## Open and close

```java
StoreSettings settings = StoreSettings.builder()
        .rules(new LeiChecksum())
        .build();
try (DurableStore store = RocksDbStores.open(Path.of("/var/lib/my-app/cairn"), settings)) {
    store.schema().register(origin, definition);
    store.commit(transaction);
    // ... the application runs ...
}
```

`RocksDbStores.open` creates the directory if it does not exist, and otherwise opens the store that is in it.
The returned `DurableStore` is the same interface as that of [a durable store kept in a directory](run-a-durable-store.md), and the settings are those of an in-memory store, with the same rules about ID generators, rules without code, and *checks* and *derived attributes*, which need the module `cairn-cel` ([Write checks](write-checks.md), [Derive attributes](derive-attributes.md)).
Closing writes what RocksDB holds in memory to its files and releases the directory. A closed store refuses every call but `close`, which does nothing then. One open store holds a directory at a time, in any process: opening a second one on the same directory throws `IllegalStateException`.

Opening replays nothing: RocksDB replays its own write-ahead log, and the store then reads what it records beside the data (the schema and its history, transaction records, the receipts of idempotency keys and purge records) into memory.
Then it runs each rule it has code for once over the data, without judging the results, to learn what the rules read ([ADR-0025](../decisions/0025-run-a-rule-again-when-the-data-it-read-changes.md)), so opening takes longer the more data the rules' types hold.
A directory written in a format version this version of Cairn does not know stops the store from opening, with an exception that names the version. RocksDB stores written before the change feed, in format version 1, are refused this way; there is no migration ([ADR-0029](../decisions/0029-feed-changes-from-an-index-of-what-each-basis-changed-read-from-a-basis.md)).

## What a commit guarantees

Each commit, schema registration and purge is one RocksDB write batch, written with a synchronous write to RocksDB's write-ahead log before the call returns.
A transaction that returned committed survives a crash of the process or the machine; one that did not leaves nothing.
If writing fails, the commit throws `UncheckedIOException` and is not applied; the store then refuses further commits and schema registrations until it is opened again, while reads go on. Give transactions that you retry after a failure an idempotency key.
Every commit waits for the disk to sync, as in a durable store kept in a directory.

`writeSnapshot()` writes what RocksDB holds in memory to its files and returns the latest basis; there are no snapshot files. Calling it now and then shortens the write-ahead log that opening replays.

## The directory

```text
lock          held while a store is open, as for stores kept in a directory
rocksdb/      RocksDB's database: data files (*.sst), its write-ahead log (*.log), MANIFEST, CURRENT, LOCK, OPTIONS and LOG files
```

Why the database is in a subdirectory is in [ADR-0028](../decisions/0028-keep-a-store-in-rocksdb-with-its-metadata-as-keys-and-its-write-ahead-log-as-the-commit-log.md).
Do not change the files. Opening the directory with `Stores.durable`, or a RocksDB store on the directory of a durable store kept in a directory, throws `IllegalStateException` and changes nothing.
To back a store up, close it and copy the directory.

## Purges

A purge works as in every store ([ADR-0024](../decisions/0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md), the `purge` spec): it removes the entities, named by ID or by unique key, from all of history and leaves a purge record.
A RocksDB store writes the purge in one synchronous batch, then flushes and compacts the whole database, and opens it again so that RocksDB rewrites its MANIFEST ([ADR-0028](../decisions/0028-keep-a-store-in-rocksdb-with-its-metadata-as-keys-and-its-write-ahead-log-as-the-commit-log.md) says why), before `purge` returns. Then no file of the store holds the purged data or the RocksDB keys that held it.
Commits wait meanwhile, and the time grows with the size of the store; reads wait only while the database is opened again. A view that reads while a purge runs may see part of it.

- **If the store stops during a purge,** after the batch was written, opening the store finishes the compaction before it opens. If flushing or compacting fails, `purge` throws `UncheckedIOException`; the purge is applied, the store refuses commits, and opening it again finishes the compaction. If opening the database again fails, the store refuses every call but `close` until it is opened again.
- **Retries stay refused,** after a reopen too: the store drops the transactions it kept for idempotency keys that wrote purged data or named a purged entity, and keeps their keys as used.
- **Who and why stay,** and **copies elsewhere** keep the data, as for [durable stores kept in a directory](run-a-durable-store.md#purges). Deleted files can stay readable on the disk itself, as with any file system.

## Limits

- The data of entities and relationships is read from RocksDB, but the store keeps its schema history, transaction records, receipts of idempotency keys, purge records and the reads of rules in memory, and those grow with every commit.
- RocksDB runs with its default options, and nothing groups commits; tuning comes later.
- The [agent server](serve-a-store-to-ai-agents.md) serves an in-memory store, not a RocksDB store, and durable stores kept in a directory cannot be migrated to RocksDB stores yet.
