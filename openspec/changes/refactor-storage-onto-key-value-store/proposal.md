# Proposal

## Why

ADR-0011 puts storage behind an ordered key-value interface, with the model logic in one engine and the in-memory store as the reference implementation, so that a RocksDB store behaves identically by construction. The engine predates that interface: it keeps each entity's whole timeline once per basis in Java maps, which grows with the square of an entity's versions, cannot exceed memory, and scans every entity for listings, navigation and uniqueness. ADR-0027 decides the key layout; this change moves the engine onto it, so that the next change can add RocksDB as a second backend.

## What Changes

- An internal ordered key-value interface in `cairn-core`: get, ordered range scans and atomic batches of puts and deletes over byte strings, with an in-memory implementation.
- The engine stores entities and relationships as ADR-0027 lays them out: a current timeline per entity or relationship, history rows of segment versions with the bases that inserted and superseded them, and indexes of types, relationship ends and unique values.
- Views, transactions, rules, queries, purges and durable stores read and write through it. Listings, navigation, uniqueness and unique keys read index ranges instead of scanning.
- Durable stores keep their file formats: the commit log and snapshot files are unchanged, and recovery writes what they hold through the same layout.

No behavior changes: every existing test passes unchanged, apart from tests of internal classes that the layout replaces.

### Non-goals

- The RocksDB store, and exporting the interface for other modules: the next change.
- A cache of decoded timelines, or keys that skip superseded rows: later, when measured.
- Moving the schema registry, transaction records, idempotency keys, purge records or the reads of rules onto the interface.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None: a refactor. The specs describe behavior that stays the same.

## Impact

- **Decisions:** ADR-0027, accepted under the standing approval with this proposal; it refines ADR-0011 and keeps ADR-0007, ADR-0008, ADR-0009, ADR-0022 and ADR-0024.
- **Code:** `cairn-core` only: a key-value package, key and value encodings, and the engine's stored state, views, preparation, purges, snapshots and recovery on top of them. No API change and no new dependency.
- **Docs:** the architecture page (building blocks, runtime view, risks), and the glossary if it needs a term for the layout.
