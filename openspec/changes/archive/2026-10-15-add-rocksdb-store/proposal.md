# Proposal

## Why

Every store keeps all its data in memory today; durable stores add a commit log and snapshot files. ADR-0011 names RocksDB as the backend for data that does not fit in memory, and the engine now keeps its data as keys over an ordered key-value interface (ADR-0027), so a RocksDB store needs no change to the engine's model logic. ADR-0028 decides how it keeps its metadata, makes commits durable, purges and is packaged.

## What Changes

- A subproject `cairn-rocksdb` with `RocksDbStores.open(directory, settings)`, which returns a `DurableStore` kept in RocksDB.
- `cairn-core` gains a persistent variant of its key-value interface (flush, compact, close) and stores over it that keep their metadata as keys and write each commit as one synchronous batch; both are exported only to `cairn-rocksdb`.
- A RocksDB store behaves as an in-memory store in every capability: the scenario tests run against both backends, and an equivalence test compares them at every basis.
- Purges in a RocksDB store compact its files so that none holds the purged data.

### Non-goals

- A RocksDB store for the agent server, backups through checkpoints, grouping commits, and tuning RocksDB: later.
- Migrating directory stores to RocksDB stores.
- Caches of decoded timelines.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `durability`: stores kept in RocksDB.
- `purge`: purges in RocksDB stores.

## Impact

- **Decisions:** ADR-0028, accepted under the standing approval with this proposal; it relies on ADR-0011, ADR-0027, ADR-0007, ADR-0022 (the codecs of its records), ADR-0024 and ADR-0025, and adds the RocksDB Java library, which the user agreed to.
- **Code:** a new subproject `cairn-rocksdb` (module `io.github.enricoag1982.cairn.rocksdb`); in `cairn-core`, the persistent key-value interface, metadata keys, a factory of stores over it and qualified exports; build logic for the subproject and for running the scenario tests against both backends; the version catalog gains `rocksdbjni`.
- **Docs:** a guide for RocksDB stores (or a section of the durable store guide), the architecture page, AGENTS.md, README.md, the glossary and `openspec/config.yaml`.
