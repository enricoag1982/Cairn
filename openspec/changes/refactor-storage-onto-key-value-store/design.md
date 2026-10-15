# Design

## Context

`InMemoryStore` keeps `NavigableMap<EntityId, StoredEntity>` and `NavigableMap<RelationshipId, StoredRelationship>`; each `Stored` holds a `ConcurrentSkipListMap<Long, Timeline>` of whole timelines by basis. `StoreView` reads `stored.at(basis).at(effectiveAt)` and scans the maps for listings and navigation; `Workspace` and `Preparation` read the same maps; `StoreImage` writes snapshot files from them and `Restorer` fills them at recovery. ADR-0027 decides the layout that replaces the maps; ADR-0011 the interface.

## Goals / Non-Goals

**Goals:** the layout of ADR-0027 behind an ordered key-value interface, with identical behavior; reads of the latest state in one lookup; listings, navigation and uniqueness by index ranges.

**Non-goals:** see the proposal.

## Decisions

### D1. The interface

A package `io.github.enricoag1982.cairn.core.kv`, not exported yet: `KeyValueStore` with `Optional<byte[]> get(byte[] key)`, `scan(byte[] from, byte[] to)` returning entries in unsigned byte order, and `apply(Batch)` for an ordered list of puts and deletes; `InMemoryKeyValueStore` on a `ConcurrentSkipListMap` with an unsigned comparator. A batch is applied before the store publishes the basis of its commit, so a reader that starts after the publication sees all of it; a reader during it may see part of it, which ADR-0027's bases make harmless, and purges keep their documented exception.

### D2. Keys

A one-byte prefix per kind, then fixed-width or length-prefixed parts so that ranges are prefixes: bases as 8-byte big-endian numbers, instants as sortable 12-byte values, schema identifiers as big-endian numbers, IDs and values length-prefixed.

| Prefix | Key | Value |
| --- | --- | --- |
| current | entity or relationship ID | type, ends of a relationship, last basis, encoded timeline with the inserting basis of each segment |
| row | ID, inserting basis, segment start | segment end, values, transaction time, recorded basis |
| superseded | ID, inserting basis, segment start | superseding basis |
| type | entity type ID, entity ID | basis that added it |
| end | entity ID, relationship type ID, direction, relationship ID | basis that added it |
| unique | attribute ID, encoded value, entity or relationship ID | basis that added it |

Values reuse the storage codecs of the commit log (`ValueCodec`, the segment encoding), so encodings exist once. Numbers in unique keys are made canonical first (a stripped decimal), so that equal int, long and decimal values, which uniqueness treats as the same, share one key.

### D3. Writing

A commit turns each written entity or relationship's previous and new timelines into one batch: the current key; a row for each new segment that the previous timeline did not have, equal by start, end, values, transaction time and recorded basis; a superseded marker for each previous segment that the new one does not have, keyed by the inserting basis that the current key holds for it; index keys for new entities, new relationships at both ends, and unique values not indexed for that entity or relationship yet. A transaction that changes nothing writes nothing. Recovery and snapshot loading write the same batches from the timelines they restore.

### D4. Reading

A `Ledger` (the engine's stored state) reads an entity or relationship at a basis: the current key when its last basis is at or below, otherwise the rows inserted at or below the basis that are not superseded at or below it. Listings read the type index of the type and its subtypes, navigation the end index of the relationship type and its subtypes in the direction asked (both for symmetric types), and uniqueness and unique keys the unique index; each candidate is then read at the basis. `ProvisionalView` keeps finding unique keys by listing the entities of the key's type through the type index, so that a rule that resolves a key still records a read of that type (ADR-0025); `StoreView` and `Workspace` read the unique index. Results keep today's order by ID. `StoreView`, `ProvisionalView`'s committed part, `Workspace`, `Cascades`, `PurgeCheck`, `Rechecks`, `InMemoryStore.learnReads`, `StoreImage` and `Restorer` use the `Ledger` instead of the maps.

### D5. Purges

A purge deletes, in one batch, the current keys, rows and markers of what it removes, the index keys of those entities and relationships at both ends, and the unique keys of every value they held; tests check that no key mentions a purged ID or a purged unique value.

### D6. Durable stores

The log and snapshot formats do not change. `StoreImage` writes each entity's timelines by basis, rebuilt from rows at the bases where it changed; `Restorer` writes them back through D3. The equivalence tests of durable stores and in-memory twins keep checking that both read alike.

## Risks / Trade-offs

- **Speed:** encoding and decoding make the in-memory store slower. Measured serially per test class before and after: the existing `cairn-core` tests took 17.0 s and 19.8 s (+16%, mostly the durable tests), `cairn-documents` 8.2 s and 7.8 s, and the HR example 2.1 s and 2.2 s; the whole parallel build took 2 min 8 s and 1 min 49 s. A cache of current timelines can follow.
- **Scope:** the engine's stored state changes everywhere it is read; the full test suite, unchanged, is the safety net, and new tests pin the keys, the reconstruction at every basis and purges.
