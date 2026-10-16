---
status: accepted
date: 2026-10-16
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Name entities to purge by unique keys that resolve over all of effective time

## Context and Problem Statement

An erasure request starts from what the requester knows, such as an employee number or an LEI, but a purge names entities by ID only ([ADR-0024](0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md)), so the caller first finds the ID with a view; the [HR domain](../reference/hr-domain.md) lists this gap.
Writes already name entities by unique keys ([ADR-0026](0026-refer-to-entities-by-unique-keys-resolved-at-commit.md)), which resolve at the start of the write's effective period. A purge has no effective period: it removes whole histories, and the entity to erase has often ended, such as a former employee. A purge cannot be undone, so what a key names in a purge is costly to get wrong. The change `add-purges-by-unique-key` raised it.

## Decision Drivers

- A purge never removes an entity that the caller did not mean: it cannot be undone
- A key finds an entity that has ended, as erasure requests often concern
- The key's value is personal data: the store keeps it nowhere after the purge, in any kind of store
- The same rules as keys in writes wherever they apply (ADR-0026)

## Considered Options

- Keys that resolve over all of effective time, to the one entity that ever held the value, with an optional effective time, and that are refused when several entities held the value
- Keys that resolve at an effective time that the caller must give, as views find them
- Keys that resolve at the purge's transaction time as effective time
- Keys that purge every entity that ever held the value

## Decision Outcome

Chosen option: "Keys that resolve over all of effective time, refused when several entities held the value", because it finds ended entities without asking the caller for a time, and never guesses between entities.

- **What a purge names.** A purge names entities by ID, by unique key, or both. A key is the same `UniqueKey` as in writes, checked the same way: one that names no unique attribute of an entity type, or whose value does not fit, is `INVALID_UNIQUE_KEY`.
- **How a key resolves.** Against the store at the latest basis, before the purge: a key names the entities of its type, or of a subtype, that hold the value at some effective time. If exactly one does, the key names it; if none does, the purge is refused with `UNIQUE_KEY_NOT_FOUND`; if several did at different times, it is refused with a new problem, `UNIQUE_KEY_AMBIGUOUS`, that names them with the periods in which they held the value.
- **With an effective time.** A key may come with an effective time. It then names the entity that holds the value at that time, as a view finds it; ambiguity cannot arise.
- **What is kept.** The store resolves keys before it writes anything, so its log, its files, the purge record and the change feed hold IDs and numbers only, never the key or its value. The result returns, once, which entity each key named, with the IDs of what was removed.
- **Everything else** is as for purges by ID: an entity that a key names is removed with its relationships and the targets of its compositions, and the minimums are checked over what remains.

### Consequences

- Good, because an erasure request goes from a number or an LEI to a purge in one call, also for entities that have ended.
- Good, because a value that moved between entities never removes the wrong one: the caller sees the candidates and their periods, and chooses.
- Good, because no file of the store keeps the value the caller named.
- Bad, because the same key resolves differently in a purge and in a write: over all of effective time here, at the start of the write's period there.
- Bad, because a value that only older bases recorded, such as a corrected mistake, is not found; a view at an older basis finds the entity's ID.
- Bad, because the in-memory store scans the holders of the value, as it does for writes, until storage gets indexes.

### Confirmation

- Scenario tests purge by key, refuse keys that name nothing or several entities, purge with an effective time, and show that no file of a durable store or a RocksDB store holds the value after a purge by key.
- The HR example erases Gus by his employee number (HR-12).

## Pros and Cons of the Options

### An effective time that the caller must give

- Good, because it is the rule of views, with nothing new to learn.
- Bad, because the caller must know when the entity held the value, which erasure requests rarely say, and a time when it held none finds nothing.

### The purge's transaction time as effective time

- Good, because the caller gives nothing more.
- Bad, because it does not find entities that have ended, which erasure often concerns.

### Every entity that ever held the value

- Good, because it never asks the caller to choose.
- Bad, because it removes entities that the caller may not have meant, and a purge cannot be undone.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, and their go-ahead of 2026-10-16 to take the gaps that remained open.
- It adds to ADR-0024 the ways a purge names entities, and to ADR-0026 how keys resolve where there is no effective period; both stay in force.
- Agent tools offer no purges yet (`AgentPolicy`), so purges by key are in Java only; JSON payloads for purges can follow.
