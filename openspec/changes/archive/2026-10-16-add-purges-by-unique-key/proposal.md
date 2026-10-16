# Proposal

## Why

An erasure request starts from what the requester knows, such as an employee number or an LEI, but a purge names entities by ID only. So the caller first finds the ID through a view, then purges it. The HR domain page lists this gap: "Purges by unique key".

## What Changes

- A `Purge` names entities by unique key too, with `Purge.Builder.entity(UniqueKey)` and `entity(UniqueKey, Instant)`. Generated Java types already make the keys, such as `Employment.byEmployeeNumber("E-007")`.
- A key without an effective time resolves over all of effective time, at the latest basis, to the one entity of its type, or of a subtype, that ever held the value. With an effective time, it resolves to the entity that holds the value then.
- A key that is invalid, names nothing, or names several entities that held the value at different times refuses the purge with a problem at the key. The new problem `UNIQUE_KEY_AMBIGUOUS` names the candidates with their periods.
- `PurgeResult.Purged` returns which entity each key named, once. The purge record, the change feed and the store's files keep no key or value.
- HR-12 erases Gus by his employee number.

### Non-goals

- Purges in JSON payloads or agent tools: agent tools offer no purges yet.
- Finding values that only older bases recorded.
- Keys of relationships, or keys over several attributes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `purge`: purging by unique key.

## Impact

- **Decisions:** ADR-0033, accepted under the standing approval: keys in purges resolve over all of effective time and are refused when ambiguous. It adds to ADR-0024 and ADR-0026, which point to it.
- **Code:** `cairn-api` (`Purge`, `PurgeKey`, `PurgeResult`, `ProblemCode`); `cairn-core` (`PurgeCheck` and the purge in the stores); `cairn-example-hr` (HR-12).
- **Docs:** the glossary (Purge), the purge sections of the durable store and RocksDB guides, the HR domain page (HR-12, gaps), and the architecture page.
