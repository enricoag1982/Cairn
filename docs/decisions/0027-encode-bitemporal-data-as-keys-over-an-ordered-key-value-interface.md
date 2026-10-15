---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Encode bitemporal data as keys over an ordered key-value interface

## Context and Problem Statement

[ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md) puts storage behind an ordered key-value interface, with all model logic in the engine, the in-memory implementation as the reference, and RocksDB next. The engine was built before that interface: each entity and relationship keeps, in Java maps, its whole effective timeline once per basis that changed it, and listings, navigation and uniqueness scan every entity. That grows with the square of an entity's number of versions, cannot hold more data than memory, and has no form a key-value store could keep.
Before a RocksDB store can exist, the engine needs a key layout: how timelines on two time axes ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)), whole-entity versions ([ADR-0009](0009-version-whole-entities-rather-than-single-attributes.md)), relationships and their indexes become keys and values. Once a store writes it to disk, changing it needs a migration, so it is costly to change. The change `refactor-storage-onto-key-value-store` raised it.

## Decision Drivers

- Views at any basis read exactly what they read today, and views never wait for the writer ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md))
- Storage grows with the number of changes, not with their square
- Reading the latest state, the common case, costs one lookup per entity or relationship
- Listings, navigation, uniqueness and unique keys read a range of keys instead of every entity
- Backends implement a small interface and know nothing of the model; purges can remove every key of what they purge ([ADR-0024](0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md))

## Considered Options

- A current timeline per entity, plus history rows of segment versions with the bases that recorded and superseded them, plus index keys
- The whole timeline per entity and basis, as now
- History rows only, without a current timeline
- Relying on the key-value store's own snapshots for transaction time

## Decision Outcome

Chosen option: "A current timeline per entity, plus history rows of segment versions with the bases that recorded and superseded them, plus index keys", because it is the only option that reads the latest state in one lookup and grows only with the number of changes.

- **The interface.** An ordered key-value store of byte strings, compared as unsigned bytes: get, a range scan in order, and an atomic batch of puts and deletes. Readers that start after a batch is applied see all of it; readers do not need the store's snapshots, because every value says the bases it belongs to.
- **The current timeline.** One key per entity or relationship holds its type, for a relationship its ends, the basis of the last transaction that changed it, and its whole effective timeline at that basis, with the basis that inserted each segment's history row. A view at a basis at or above that one reads it directly.
- **History rows.** Each segment of a timeline that a transaction writes, and that was not in the timeline before, is a row keyed by the entity or relationship, the basis that inserted it and the segment's start, holding its end, values, transaction time and recorded basis. When a later transaction removes or changes that segment, a marker keyed the same way records the basis that superseded it. A view at an older basis rebuilds the timeline from the rows inserted at or below its basis and not superseded at or below it. A view that finds the current timeline newer than its basis does the same, so a commit never disturbs a view.
- **Indexes.** The entities of each type, the relationships of each entity by relationship type and direction, and each value of a unique attribute with the entities that ever held it, each a key with the basis that added it. They give candidates; the timelines decide.
- **Identifiers.** Keys use schema identifiers, as the commit log does ([ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md)), and a prefix per kind of key, so a backend can tell them apart without knowing the model.
- **What stays outside.** The schema registry, transaction records, idempotency keys, purge records and the reads of rules stay where they are in this decision; the RocksDB store decides how it keeps them.
- **Purges** delete every key of what they purge: current timelines, history rows, markers and index entries.

### Consequences

- Good, because the latest state of an entity costs one lookup, and history costs one range scan of that entity's rows.
- Good, because storage grows with the number of segments that transactions write, and listings, navigation and uniqueness read index ranges.
- Good, because the in-memory implementation and RocksDB share every line of model logic, as ADR-0011 wants.
- Bad, because every commit writes the whole current timeline of what it changes again, so an entity with very many versions makes its commits larger.
- Bad, because reading an old basis rebuilds timelines from rows, which is slower than today's maps.
- Bad, because the in-memory store encodes and decodes values it used to keep as objects, so it gets slower until it caches decoded timelines.
- Bad, because the index of unique values keeps every value an entity ever had until a purge.

### Confirmation

- Every existing test passes against the engine on the in-memory key-value store, with no change in behavior.
- Tests check the keys a commit writes, that a view at every basis reads the same timelines as before, and that a purge leaves no key of what it purged.

## Pros and Cons of the Options

### The whole timeline per entity and basis

- Good, because a view at any basis reads one key.
- Bad, because storage grows with the square of an entity's versions, the risk the architecture page lists.

### History rows only

- Good, because nothing is written twice.
- Bad, because reading the latest state scans an entity's whole history.

### The key-value store's own snapshots

- Good, because transaction time comes for free while a snapshot is open.
- Bad, because stores keep snapshots only while they are open and compact old versions away, so views at old bases, which Cairn keeps forever, would be lost.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `refactor-storage-onto-key-value-store`.
- It refines ADR-0011, which stays in force. A RocksDB store follows in its own change, with its own decisions about durability and files.
- A cache of decoded current timelines, and ranges that skip superseded rows, can follow when measurements ask for them.
- Revised on 2026-10-15, before `refactor-storage-onto-key-value-store` was archived, after building it: the current key also holds the basis that inserted each segment's row, because a segment's recorded basis is not always that basis (a segment cut later is inserted again), and without it a commit would scan an entity's history to find the rows to mark superseded.
