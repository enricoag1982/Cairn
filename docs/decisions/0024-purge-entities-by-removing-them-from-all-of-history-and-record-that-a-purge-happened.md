---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Purge entities by removing them from all of history, and record that a purge happened

## Context and Problem Statement

Cairn keeps every version of every entity on two time axes ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)): nothing is ever changed or deleted, and a view at an old basis reads what was known then. A system of record for enterprise data also holds personal data, and laws such as the GDPR require that some of it can be erased for good, including its history.
RD-11 of the [reference domain](../reference/reference-domain.md) asks for it: purging Alice removes her facts from all of history and leaves a record that a purge happened. [ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md) already says that purges need a person's approval when agents ask for them.
A purge contradicts the immutability that everything else relies on, in memory and in durable stores ([ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md)), so how it does so is costly to change. The change `add-purge` raised the question.

## Decision Drivers

- After a purge, the purged data cannot be read through any view, at any basis, nor recovered from the store's files
- What remains stays consistent: no relationship to a purged entity, no entity left without a relationship that its type requires
- The purge itself is on record: who, for whom, why, when, and which IDs
- No new dependencies, and no cost for stores that never purge

## Considered Options

- Remove the entities from all of history, rewrite the files, and record the purge
- Crypto-shredding: encrypt each entity's values with a key of its own, and delete the key
- Redaction: keep every version, replace the values with a marker
- No purge: end-date the entities and leave their history

## Decision Outcome

Chosen option: "Remove the entities from all of history, rewrite the files, and record the purge", because it is the only option that erases the data itself with no machinery that every other read and write would pay for.

- **What a purge removes.** The given entities with every version at every basis, every relationship from or to them with all its versions, and, as end-dating does, the targets of their compositions. Their IDs are kept as purged and are never given out or accepted again.
- **When a purge is refused.** If what remains would break a relationship type's minimum at the other end, such as an account left without a holder, the purge is rejected with problems that name the entities to purge too. Rules do not run: a purge writes no versions.
- **A record of the purge.** A purge is a transaction of its own, with a basis and a transaction time, a principal, a party acted for and a reason, which every store lists with the purged IDs. Transaction records of earlier transactions stay; only their data goes.
- **History changes, once.** After a purge, views at every basis, including views already open, read as if the purged entities had never been recorded. This is the one exception to immutability, and it is visible as a purge record.
- **Idempotency keys** of transactions that wrote purged data stay used, and a transaction that repeats such a key is rejected with a problem, so that a retry cannot bring the data back.
- **Durable stores** write the purge to the log, then a snapshot file without the purged data, and then delete every older snapshot file and log segment, before the purge returns.
- **People decide.** Purging is part of the Java API. Agent tools do not offer it yet; when they do, the approval policy requires a person's approval by default.

### Consequences

- Good, because the data is gone from memory and from the store's files, not hidden.
- Good, because what remains is consistent, and the purge itself is on record.
- Good, because stores that never purge pay nothing.
- Bad, because a view at an old basis can read differently after a purge than before it, so results that were reported earlier cannot always be reproduced.
- Bad, because a purge in a durable store rewrites the whole store's files, which takes time proportional to the store's size, under the writer lock.
- Bad, because copies outside the store, such as backups, change feeds and data read before, keep the data; purging them is the operator's task.

### Confirmation

- Tests purge Alice after the reference stories and show that no view at any basis, no timeline, no query and no file of a durable store holds her data or her relationships, and that the purge is listed.
- Tests show the purges that are refused, and that purged IDs and keys cannot be used again.

## Pros and Cons of the Options

### Crypto-shredding

- Good, because files and logs stay append-only, and erasure is instant: deleting a key.
- Bad, because every read and write pays for encryption, and the store needs key management that survives crashes and backups.
- Bad, because relationships and IDs are not encrypted values, so they still show who was linked to whom.

### Redaction

- Good, because the structure of history stays, and old views keep the same shape.
- Bad, because relationships, IDs and the timing of changes remain, which can still identify a person.

### No purge

- Good, because history stays immutable without exception.
- Bad, because the store cannot meet a legal duty to erase personal data.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-purge`.
- A purge tool for agents, behind the approval policy, can follow later.
- This decision is an exception to [ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md) and [ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md), which stay in force for everything else and refer to it.
