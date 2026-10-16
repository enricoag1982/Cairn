---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Refer to entities by unique keys, resolved at commit

## Context and Problem Statement

Writes and reads name entities by their opaque IDs ([ADR-0019](0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md)). People and agents usually know something else: an account number, a unit code, an employee number, all of them unique attributes. The [HR domain](../reference/hr-domain.md) shows the cost: the agent of story HR-13 runs four queries to turn codes and names into IDs before it can write its payload, and erasure requests start from a name or a number.
A value of a unique attribute names at most one entity at each point in effective time, but over time it can move from one entity to another ([`entity-versions`](../../openspec/specs/entity-versions/spec.md), "Reusing a value after it ends"). How a reference by such a value is resolved, and when, decides what a write changes, so it is costly to change later. The change `add-unique-key-references` raised it.

## Decision Drivers

- Agents and people write what they know, without a lookup step that can go stale between reading and writing
- A reference names exactly one entity, or the write is rejected with a problem that says why
- The same result with every storage implementation, and for a retry of the same transaction
- IDs stay the identity of an entity; keys are a way to find one

## Considered Options

- A *unique key* (an entity type, a unique attribute of it and a value) as a kind of reference, resolved by the store at commit
- Keys resolved by the client, such as the payload reader, before the transaction is submitted
- Natural keys as identity: entities identified by their unique attributes instead of by IDs

## Decision Outcome

Chosen option: "A unique key as a kind of reference, resolved by the store at commit", because the store resolves it inside the single writer ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)), so the entity it finds is the one the transaction changes.

- **What a key is.** An entity type, an attribute that is unique in that type, and a value of the attribute's value type. It names the entity of that type, or of one of its subtypes, that has the value. Keys name entities only.
- **When it resolves.** A write's key resolves at the start of the write's effective period, against the store as committed before the transaction. An entity that the same transaction creates is named by its temporary ID, as before.
- **When it does not.** A key that names no unique attribute of an entity type, or whose value does not fit the attribute, is invalid; a key that no entity has at that time names nothing. Either rejects the transaction with a problem at the reference.
- **What it becomes.** Once resolved, the write concerns the entity's ID: problems about it name it by its ID, and the transaction as submitted, which the log of a durable store and idempotency keys keep, keeps the key.
- **Reads.** A view finds the entity that has a key at its effective time. Agent tools that take an entity's ID and an effective time take a key too, and generated Java types make keys for their unique attributes.

### Consequences

- Good, because writes and reads use the names that people and agents know, and the agent of HR-13 writes its payload without queries.
- Good, because resolving at commit cannot act on a value that moved to another entity after the caller looked it up.
- Good, because IDs stay stable when a key value changes, so relationships and history are unaffected.
- Bad, because a key names a different entity at a different time, so the same payload can change different entities on different effective dates.
- Bad, because the in-memory store finds a key by scanning the type until storage gets indexes ([ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md)).
- Bad, because a key cannot name what the same transaction creates or renames; temporary IDs do that.

### Confirmation

- Tests write and read the reference domain by account numbers and LEIs, including an LEI that moves from one organization to another.
- The HR example's agent story resolves its codes through keys, without queries.

## Pros and Cons of the Options

### Keys resolved by the client

- Good, because the store does not change.
- Bad, because the value can move to another entity between the lookup and the commit, and the write then changes the wrong entity.
- Bad, because every client, payload reader, agent tool and Java caller repeats the lookup.

### Natural keys as identity

- Good, because there is one kind of name for an entity.
- Bad, because unique values change and move between entities over time, so identity would change with them, against [ADR-0019](0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md).

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-unique-key-references`.
- Keys over several attributes, keys of relationships, and purges by key can follow the same rules later.
- Purges take keys that resolve over all of effective time, as [ADR-0033](0033-name-entities-to-purge-by-unique-keys-that-resolve-over-all-of-effective-time.md) decides.
