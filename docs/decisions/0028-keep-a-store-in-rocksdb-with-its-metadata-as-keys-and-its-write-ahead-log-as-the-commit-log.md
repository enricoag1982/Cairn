---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Keep a store in RocksDB with its metadata as keys and its write-ahead log as the commit log

## Context and Problem Statement

The engine keeps its data as keys over an ordered key-value interface ([ADR-0027](0027-encode-bitemporal-data-as-keys-over-an-ordered-key-value-interface.md)), with an in-memory implementation. [ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md) names RocksDB as the next backend, for data that does not fit in memory. Durable stores today keep everything in memory and make it durable with a commit log of effects and snapshot files ([ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md)).
A RocksDB store needs to decide where the parts outside the key layout live (the schema and its history, transaction records, idempotency keys, purge records, the latest basis), how a commit becomes durable, what a purge does to RocksDB's files, and how the code is packaged with a native library. Its files then have a format that is costly to change. The change `add-rocksdb-store` raised it.

## Decision Drivers

- Identical behavior to the in-memory store, shown by running the same scenario tests against both (ADR-0011)
- A commit is on disk before it returns, and a crash loses no committed transaction
- One atomic write per commit, so state and metadata never disagree after a crash
- A purge leaves no file holding the purged data ([ADR-0024](0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md))
- `cairn-core` keeps its only runtime dependency, RE2/J; applications that do not use RocksDB do not load a native library

## Considered Options

- RocksDB holds the key layout and the metadata as keys, and each commit is one synchronous write batch, so RocksDB's write-ahead log is the commit log
- RocksDB holds the key layout, and Cairn's commit log files of ADR-0022 hold the metadata and make commits durable
- RocksDB only replaces the snapshot files of directory stores, with all data still in memory

## Decision Outcome

Chosen option: "RocksDB holds the key layout and the metadata as keys, and each commit is one synchronous write batch", because one atomic, synchronous write per commit makes state and metadata durable together, with nothing to reconcile after a crash.

- **Packaging.** A subproject and module `cairn-rocksdb` depends on `cairn-core` and the RocksDB Java library, which bundles the native library for the common platforms. `cairn-core` exports its key-value interface and a factory of stores over it only to that module. Applications open a store with `RocksDbStores.open(directory, settings)`, which returns a `DurableStore`.
- **Metadata as keys.** Besides ADR-0027's keys, the store keeps, under prefixes of their own: a format version, the latest basis and transaction time, each schema registration with the identifiers it assigned, each transaction record, each idempotency key with its transaction and receipt, each purge record, and the digests of purged IDs. They hold what the commit log of ADR-0022 holds for the same purpose, encoded with the same codecs, except that no record keeps the effects of a transaction: the key layout holds the state.
- **Commits.** A commit writes its keys and its metadata as one RocksDB write batch with a synchronous write to the write-ahead log, before the store publishes its basis. RocksDB replays its log when it opens; Cairn then reads the metadata, which needs no replay of transactions, and learns what its rules read ([ADR-0025](0025-run-a-rule-again-when-the-data-it-read-changes.md)). A write that fails leaves the store refusing commits until it is opened again, as for directory stores.
- **No snapshot files.** `writeSnapshot` flushes what RocksDB holds in memory to its files and returns the latest basis.
- **Purges** write their deletes and their record in one synchronous batch, then flush and compact the whole key range so that no data file keeps the deleted values, with the write-ahead log not archived, before the purge returns. Tests search every file of the directory for the purged data.
- **One store per directory,** through RocksDB's lock and the same lock file as directory stores. A store refuses to open a directory whose format version it does not know.
- **Conformance.** The scenario tests of the engine's capabilities run against both backends, as do the equivalence tests that compare stores at every basis.

### Consequences

- Good, because data larger than memory fits, and opening a store does not replay its history.
- Good, because state and metadata are written in one atomic batch, so a crash cannot leave them apart.
- Good, because the engine is unchanged: the backend implements four operations.
- Bad, because every commit waits for a synchronous write, as directory stores do; grouping commits comes later.
- Bad, because a native library joins the build and the applications that use this module, with its platforms and its native access warnings on recent Java versions.
- Bad, because a purge compacts the whole store under the writer lock, which takes time proportional to the store's size.
- Bad, because transaction records and idempotency keys grow with every commit, as they do in memory.

### Confirmation

- The scenario tests of `transactions`, `entity-versions`, `views`, `relationships`, `entity-rules`, `queries`, `purge` and `durability` pass against a RocksDB store, and an equivalence test compares it with an in-memory store at every basis after random transactions.
- A test stops a process that committed without closing its store, and the reopened store holds every commit.
- A test searches every file of a RocksDB store's directory for purged data.

## Pros and Cons of the Options

### Cairn's commit log for durability and metadata

- Good, because the log format of ADR-0022 is reused as it is.
- Bad, because two systems write each commit, and a crash between them leaves state and log apart, which recovery must reconcile.

### RocksDB in place of snapshot files only

- Good, because it is the smallest change.
- Bad, because data still has to fit in memory, which is the reason for a key-value store on disk.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-rocksdb-store`; the user agreed to the RocksDB store and its native dependency on 2026-10-15.
- A change feed can start from the transaction records by basis later, with keys of its own for what each basis changed; grouping commits, backups through RocksDB checkpoints and a cache of decoded timelines can follow when needed.
- Revised on 2026-10-15, before `add-rocksdb-store` was archived, after building it:
  - The metadata also keeps the idempotency keys whose transactions a purge dropped, so that a retry stays refused after the store is reopened, and a key that marks a purge whose compaction has not finished.
  - RocksDB's MANIFEST records the smallest and largest key of every data file, which can be a purged ID or unique value, until RocksDB rewrites it when it opens the database. So a purge also closes and opens the database again after compacting, before it returns.
  - Opening a store that holds the mark of an unfinished compaction, because it stopped during a purge or a compaction failed, compacts before the store opens, as recovery completes a purge in a directory store.
  - The database lives in the subdirectory `rocksdb` of the store's directory, because RocksDB's lock file `LOCK` and the directory's `lock` are the same file on file systems that ignore case. A store refuses a directory that the other kind of store wrote.
  - The engine's model logic is unchanged, as the consequences say, but a store over a persistent key-value store joins the engine, and a backend implements six operations: get, scan, apply, flush, compact and close.
  - Bad, because readers wait while a purge reopens the database, and a reopening that fails leaves the store refusing every call but closing until it is opened again.
  - Bad, because the reopening relies on RocksDB writing a new MANIFEST when it opens a database; a test searches every file after a purge, so an upgrade that changes this fails the build. RocksDB keeps its old info logs, which hold no data.
  - Bad, because RocksDB's write-ahead log is not kept once flushed and transaction records hold no effects, so a RocksDB store has no log that replicas or a change feed could read, unlike the commit log of [ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md) and [ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md). A change feed needs keys of its own, such as an index of what each basis changed.
- [ADR-0029](0029-feed-changes-from-an-index-of-what-each-basis-changed-read-from-a-basis.md) adds a kind of key to the layout, one per changed entity or relationship and basis, from which the change feed is read.
