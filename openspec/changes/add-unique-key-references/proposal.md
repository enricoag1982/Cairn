# Proposal

## Why

Writes and reads name entities only by opaque IDs, but people and agents know account numbers, unit codes and employee numbers, which are unique attributes. In the HR example, the agent of HR-13 runs four queries to turn codes and names into IDs before it can write, and a lookup done before a commit can go stale before the commit.
ADR-0026 decides that a *unique key*, an entity type with one of its unique attributes and a value, is a kind of reference that the store resolves at commit.

## What Changes

- Writes name existing entities by unique keys as well as by IDs and temporary IDs; the store resolves a key at the start of the write's period, against the store as committed, and rejects a key that is invalid or names no entity. The receipt lists what each key resolved to.
- A purge drops the stored writes of every transaction that named a purged entity by key, as it does for those that named it by ID, so that no key value of purged data stays.
- Views find the entity that has a unique key at their effective time.
- Payloads write a key as `{ entityType: Account, key: { number: A-1 } }`; `cairn_read_entity`, `cairn_navigate` and `cairn_traverse` take a key in place of an ID.
- Generated Java types get a `by<Attribute>` method for each unique attribute, such as `Account.byNumber("A-1")`.
- A rule that finds an entity by key reads the entities of its type, so changes to them run it again.
- The HR example's agent story writes its payload with keys, without queries, and the gap leaves the HR domain page.

### Non-goals

- Keys over several attributes, keys of relationships, and purges by key (ADR-0026: later).
- Keys that name what the same transaction creates or changes: temporary IDs do that.
- Indexes: the in-memory store scans the type, as it does for uniqueness.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `transactions`: references by unique keys, how they resolve, and their problems.
- `views`: finding an entity by a unique key.
- `json-payloads`: keys in payloads.
- `agent-tools`: keys in the read tools.
- `java-types`: a method per unique attribute that makes a key.
- `entity-rules`: a lookup by key is a read of the entities of the type.
- `purge`: transactions that named a purged entity by key count as having written purged data.

## Impact

- **Decisions:** ADR-0026, accepted under the standing approval with this proposal. It relies on ADR-0019 (IDs stay the identity), ADR-0007 (resolution inside the single writer), ADR-0022 (the log keeps the transaction as submitted) and ADR-0025 (reads of rules).
- **Code:** `cairn-api` gains `UniqueKey`, a third kind of `Ref`, `View.entity(UniqueKey)`, the key resolutions of a `Receipt` and two problem codes; `cairn-core` resolves keys in transactions, finds them in views, records them in the log and records rule reads; `cairn-documents` reads keys in payloads and locates their problems; `cairn-agent-tools` takes keys in three tools; `cairn-codegen` generates the `by` methods. No new dependencies.
- **Docs:** the glossary (unique key), the guides for payloads and agents, generated types and queries, the architecture page and the HR domain page.
