# Proposal

## Why

Cairn can define and evolve a schema, but cannot yet store any data.
This change adds the core of a system of record: entities written in consistent transactions, with history on two time axes, and read through immutable views.
It is the first change that holds data, so it also fixes identity (ADR-0019), the shape of transactions, and what agents need to write safely: dry runs, idempotency keys, and a record of who acted, for whom and why (ADR-0017).

## What Changes

- A store, created in memory, that owns its schema registry. Schema transactions and data transactions share one sequence of bases and transaction times.
- Transactions: atomic sets of writes, committed by the single writer, with a receipt. A transaction records its principal, who the principal acts for, and a reason. Temporary IDs name entities created in the same transaction. A dry run validates without committing. An idempotency key makes retries safe.
- Entity IDs from one generator per store: UUIDv7 by default, or a deterministic generator for tests (ADR-0019). Callers may supply IDs for repeatable imports.
- Writes on entities for an effective period: create, patch (the default), replace and end-date. Corrections and future-dated changes are the same writes. Optional conflict checks against the caller's basis, per entity or per attribute (ADR-0009).
- Validation at commit, against the state the transaction would produce, at every point in effective time: concrete types, known attributes, value types, ranges, string constraints, required attributes, deprecated attributes, and uniqueness over effective time.
- Views bound to an effective time and a basis or transaction time: reading an entity, an entity's timeline, and every entity of a type including its subtypes. Values are reported under the attribute names of the view's schema.
- `Literal` is renamed `Value`, because the same typed values now hold data as well as examples and range bounds.

### Non-goals

- Running rules at commit: `add-entity-rules`.
- JSON payloads and a JSON Schema per entity type: `add-json-payloads`.
- Relationships, roles and traversal: `add-relationships`.
- Durable storage and the key-value interface of ADR-0011: the store is in memory only.
- Feature flags, limits, tenants and the change feed (ADR-0013): later changes.
- Day granularity of effective time per entity type (ADR-0008): later, with a schema property.
- Purge (ADR-0008): designed, built later.

## Capabilities

### New Capabilities

- `transactions`: committing writes atomically, receipts, temporary and supplied IDs, ID generators, who and why, dry runs and idempotency
- `entity-versions`: creating, patching, replacing and end-dating entities on two time axes, corrections, conflict checks, and validation of values
- `views`: reading through immutable views at an effective time and a basis or transaction time

### Modified Capabilities

- `schema-evolution`: schema transactions share the store's sequence of bases and transaction times when the registry belongs to a store

## Impact

- **Decisions:** ADR-0007 (single writer, snapshot readers), ADR-0008 (two time axes), ADR-0009 (whole-entity versions, conflict checks), ADR-0013 (views), ADR-0017 (dry runs, idempotency, who and why) and ADR-0019 (entity IDs). All are accepted.
- **Code:** `cairn-api` gains the package `io.github.enricoag1982.cairn.data` (store, transactions, writes, views, values, IDs); `cairn-core` gains the in-memory store. The rename of `Literal` to `Value` touches `cairn-api`, `cairn-core` and `cairn-documents`.
- **Dependencies:** none.
- **Docs:** glossary (dry run, idempotency key, temporary ID, receipt, principal, store), the reference domain (stories become executable), the architecture page.
