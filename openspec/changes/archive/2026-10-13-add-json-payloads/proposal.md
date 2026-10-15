# Proposal

## Why

Agents talk JSON (ADR-0017, commitment 5). To write entities and relationships they need a payload format for transactions, a JSON Schema per entity type to build valid values from, and problems that point into the payload they sent.
Reading data back must give the same format, so that an agent can read an entity, change it and write it back.
Java callers already have this through the generic API of `add-entity-versions` and `add-relationships`; this change gives everyone else the same through text.

## What Changes

- A transaction payload format in YAML 1.2, usually written as JSON: who and why, the idempotency key, and the writes (create, relate, patch, replace, end-date) with temporary IDs, entity and relationship references, effective periods, values and conflict checks.
- Reading a payload into a transaction, with values typed by the store's schema. Problems from reading, dry runs and commits are located in the payload by JSON Pointer, line and column.
- Writing receipts, entity and relationship states, timelines, navigation results and problems as JSON.
- A JSON Schema per entity type and relationship type for their values, and one for transaction payloads of a schema, generated from the schema.

### Non-goals

- Network endpoints and MCP tools: `add-agent-tools`.
- Query payloads: `add-queries-and-aggregation`.

## Capabilities

### New Capabilities

- `json-payloads`: transaction payloads, their reading with locations, JSON forms of results, and generated JSON Schemas

### Modified Capabilities

- `schema-documents`: instants with an offset are read and converted to UTC, as in payloads

## Impact

- **Decisions:** ADR-0012 (one format, YAML 1.2, which includes JSON) and ADR-0017 (entities written as JSON, checked against a JSON Schema per entity type). Both are accepted.
- **Code:** `cairn-documents` gains payload reading and writing and the JSON Schema generator. No new dependencies.
- **Docs:** glossary (payload), the architecture page.
