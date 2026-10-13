---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Identify entities by opaque IDs from one generator per store

## Context and Problem Statement

Every entity and relationship needs an identity that never changes while its attributes, and even its natural keys, change over time ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)).
Callers asked whether the kind of ID can be chosen, for example UUIDv7, UUIDv8 or something else.
We need to decide what an ID is in the API, who generates it, and whether the format can vary.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md#identity).

## Decision Drivers

- Identity is stable for the whole life of an entity, independent of its attributes
- Keys and indexes behave predictably within one store
- Imports can be repeated without creating duplicates
- Tests are deterministic
- Agents and other callers can refer to entities they create in the same transaction

## Considered Options

- Opaque IDs from one generator per store, UUIDv7 by default
- A generator chosen on every call
- Natural keys as identity
- 64-bit sequence numbers only

## Decision Outcome

Chosen option: "Opaque IDs from one generator per store, UUIDv7 by default", because it keeps identity stable and keys uniform, while letting each store pick the format it needs.

- **Opaque in the API.** An ID is a value with a canonical text form. Callers compare and store it, but never read meaning from it.
- **One generator per store.** The generator is a setting of the store, fixed when the store is created. Mixing formats in one store would make keys and indexes unpredictable.
- **Generators:** UUIDv7 (RFC 9562) by default. A store may use another generator that the engine provides, such as UUIDv8 with a custom layout, ULID or a 64-bit sequence, or a deterministic generator for tests. Relationships use the same generator as entities.
- **Temporary IDs.** Inside a transaction, a caller names new entities with temporary IDs and uses them in other writes of the same transaction. The receipt maps every temporary ID to the real one.
- **Caller-supplied IDs.** A caller may supply an ID in the store's format when creating an entity, so that imports can be repeated: creating an entity with an ID that already exists is a problem, not a second entity.
- **Natural keys are constraints, not identity.** A natural key, such as an LEI, is a unique attribute: unique at every point in effective time.

### Consequences

- Good, because identity survives every change of attributes, including natural keys.
- Good, because each store can choose compact IDs (a sequence) or IDs that are unique across systems (UUIDv7).
- Good, because temporary IDs let one transaction create a graph of new entities.
- Bad, because IDs from different stores can have different formats, so moving data between stores may need a mapping.
- Bad, because UUIDs take 16 bytes per reference; with whole-entity versions ([ADR-0009](0009-version-whole-entities-rather-than-single-attributes.md)) an ID appears once per version, which keeps the cost acceptable.

### Confirmation

- The conformance suite runs with the default generator and with the deterministic one.
- Tests show that temporary IDs resolve within a transaction and appear in the receipt, and that a supplied ID that already exists is rejected.

## Pros and Cons of the Options

### A generator chosen on every call

- Good, because each caller can pick the format it likes.
- Bad, because one store would mix formats, which makes keys, ordering and indexes unpredictable.

### Natural keys as identity

- Good, because the ID means something to people.
- Bad, because natural keys change and are sometimes missing, and an identity that changes breaks history and references.

### 64-bit sequence numbers only

- Good, because they are the most compact and are ordered.
- Bad, because they are unique only within one store, so data from several stores cannot be merged without a mapping.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap step by step without asking again. The options and the outcome follow the identity section of the API research note, discussed with the user on 2026-10-12.
- Whether storage keys use the UUIDs directly or compact internal numbers is a storage detail, decided with the durable storage changes.
- RFC 9562, UUIDs (versions 7 and 8): <https://www.rfc-editor.org/rfc/rfc9562>
