# Design

## Context

The engine (`InMemoryStore` with its `Ledger`) writes each commit as one `Batch` to a `KeyValueStore` (ADR-0027) and keeps its metadata in memory: the schema registry, transaction records, idempotency keys, purge records and digests, the commit sequence. `DirectoryStore` makes that durable with a `Journal` (the commit log) and snapshot files (ADR-0022). ADR-0028 decides a RocksDB store: metadata as keys, one synchronous batch per commit, compaction for purges, a module of its own.

## Goals / Non-Goals

**Goals:** a RocksDB store with the behavior of an in-memory store, durable per commit, with purges that leave nothing in its files; the scenario tests run against both backends.

**Non-goals:** see the proposal.

## Decisions

### D1. The persistent interface and its exports

`PersistentKeyValueStore extends KeyValueStore` adds `apply` that returns only when the batch is durable, `flush()`, `compactAll()` and `close()`. A public class in `core.kv`, for example `KeyValueStores`, opens a `DurableStore` over a `PersistentKeyValueStore` and `StoreSettings`. `cairn-core`'s `module-info` exports `core.kv` only to `io.github.enricoag1982.cairn.rocksdb`; nothing else in `cairn-core`'s API changes.

### D2. Metadata as keys

Prefixes after ADR-0027's: the format version (written when a store is created), the latest basis and transaction time, schema records by basis, transaction records by basis, idempotency keys by key (transaction as submitted and receipt), purge records by basis, purged ID digests, purged idempotency keys (so that a retry of a purged transaction stays refused after the store is reopened), and a key that marks a purge whose compaction has not finished (see D4). Values use the codecs of the commit log (`RecordCodec`, `TransactionCodec`, `DefinitionCodec`, `PurgeCodec`), without effects. The engine writes them into the same batch as the state of the commit, in core, so that any persistent backend gets them.

### D3. Commits and opening

A data commit, a schema registration and a purge each build one batch of state and metadata and `apply` it durably before publishing the basis; if `apply` throws, the transaction is not applied and the store refuses writes until it is opened again, as `DirectoryStore` does. Opening checks the format version, loads the metadata into the engine (registry history, records, keys, purges, digests, sequence), checks that the ID generator accepts the stored IDs and advances it past them as recovery does, and runs `learnReads` (ADR-0025). There is no replay of transactions.

### D4. Purges

The purge batch deletes the `Ledger`'s keys of what it removes (ADR-0027 D5), the idempotency entries that wrote or named purged data (ADR-0024, ADR-0026), and adds the purge record and digests, the purged idempotency keys and the key that marks a pending compaction; after `apply`, the store calls `flush()` and `compactAll()`, then deletes that key, before returning. The RocksDB backend opens the database with the write-ahead log not archived and compacts with the bottommost level forced, so no data file keeps a deleted value. RocksDB's MANIFEST records the smallest and largest key of every data file, which can be a purged ID or unique value, and keeps the records of deleted files until it is rewritten, which RocksDB does when it opens the database; so `compactAll` closes and opens the database again after compacting, under the backend's gate, and scans that are running go on from their last key on the reopened database. Opening a store that holds the pending-compaction key, because it stopped during a purge or a compaction failed, flushes and compacts before the store opens, and then deletes the key.

### D5. `cairn-rocksdb`

Module `io.github.enricoag1982.cairn.rocksdb`, requiring `io.github.enricoag1982.cairn.core` and the RocksDB library (`org.rocksdb:rocksdbjni`, in the version catalog). `RocksDbStores.open(Path, StoreSettings)` creates the directory, takes the same lock file as directory stores, opens RocksDB in the subdirectory `rocksdb` (RocksDB's own lock file, `LOCK`, would be the same file as `lock` on file systems that ignore case), and wraps it as a `PersistentKeyValueStore`: `get` and `scan` through RocksDB iterators in byte order, `apply` as a `WriteBatch` written with `sync`, `flush` and `compactRange`. Tests and applications run with native access enabled for the library's module.

### D6. Conformance

The scenario tests of `transactions`, `entity-versions`, `views`, `relationships`, `entity-rules`, `queries`, `purge` and `durability` build their stores through the test fixtures (`ReferenceStore` and the durable fixtures). A test backend switch, a system property read only by the fixtures, makes them build RocksDB stores in temporary directories; the build runs those test classes a second time with the switch, with `cairn-rocksdb` on the test runtime path, as part of `./gradlew build`. Tests that are about directory stores' files stay on directory stores. An equivalence test in `cairn-rocksdb` commits random transactions, registrations and purges to a RocksDB store and an in-memory store and compares every view at every basis, as the durable stores' equivalence tests do; a forked process that stops without closing tests crashes.

## Risks / Trade-offs

- **Native library:** the RocksDB jar is large and native; the build downloads it once. CI runs on Linux, where the bundled library works.
- **Build time:** running the scenario tests twice lengthens the build; only the listed capabilities run twice.
- **Purge time:** compacting the whole store under the writer lock grows with its size, as ADR-0028 accepts. Reopening the database after the compaction adds little, because the write-ahead log is empty after the flush.
- **The MANIFEST:** the reopen relies on RocksDB writing a new MANIFEST when it opens a database and deleting the old one. A test searches every file of the directory before the store is closed, so an upgrade of RocksDB that changes this fails the build.
