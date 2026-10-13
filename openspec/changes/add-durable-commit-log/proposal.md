# Proposal

## Why

Every store so far lives in memory and is gone when its process stops, so Cairn cannot yet be a system of record.
ADR-0011 puts durability in a commit log, and orders the work: the in-memory store with a file-based commit log and periodic snapshots first, a key-value store later.
ADR-0022 decides what the log holds and how a store recovers. This change builds it.

## What Changes

- A durable store: a store opened on a directory, which writes every committed transaction, of data or of the schema, to a commit log in that directory before the commit returns, and recovers its state from the directory when it is opened again.
- Recovery applies what each transaction did, without validating, running rules, or asking the clock or the ID generator, so the recovered state is the committed state.
- Snapshot files, written on request and when the store closes, so that recovery reads the newest snapshot and only the log after it. Older log segments and snapshots are deleted, keeping enough to recover if the newest snapshot is damaged.
- Crash safety: an incomplete last record, as a crash leaves it, is dropped at recovery; damage anywhere else, or an unknown format version, stops the store from opening.
- One store per directory at a time, and a store that cannot write a commit refuses further commits until it is opened again.
- Transaction times keep increasing, and new IDs never repeat recorded ones, after a store is opened again.

### Non-goals

- Grouping several commits into one write to disk, and automatic periodic snapshots: the API leaves room for both.
- A change feed, replicas and a key-value store such as RocksDB, which build on this log later (ADR-0011).
- A tool to inspect log and snapshot files.
- Purging (RD-11), which will have to rewrite the log and the snapshots.

## Capabilities

### New Capabilities

- `durability`: durable stores, recovery, snapshots, crash safety, and what holds after a store is opened again

### Modified Capabilities

None. A durable store behaves as an in-memory store in every other way.

## Impact

- **Decisions:** ADR-0011 (storage behind a commit log, the in-memory store as reference) and ADR-0022 (a log of effects and snapshots), both accepted; ADR-0022 was accepted under the standing approval, with this proposal. ADR-0007 (one writer) and ADR-0008 (two time axes) hold unchanged.
- **Code:** `cairn-api` gains `DurableStore`; `cairn-core` gains `Stores.durable`, the commit log, snapshot files and recovery, without new dependencies.
- **Docs:** a guide for running a durable store, the glossary (durable store, recovery, snapshot file), the architecture page.
