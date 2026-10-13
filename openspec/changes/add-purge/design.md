# Design

## Context

ADR-0024 decides that a purge removes entities, their relationships and the targets of their compositions from all of history, rewrites the files of durable stores, and is recorded. The in-memory store keeps one `StoredEntity` or `StoredRelationship` per ID, each with a timeline per basis, in maps that views read without locks (ADR-0007). Durable stores log the effects of each commit and write snapshot files (ADR-0022). The requirements are in `specs/purge/spec.md`.

## Goals / Non-Goals

**Goals:**

- A purge that leaves no trace of the purged data in memory or on disk, and leaves the rest consistent.
- No cost for stores that never purge.

**Non-Goals:**

- Partial purges, agent tools for purging, erasing backups.

## Decisions

### D1. API

- `cairn-api`, package `data`: `Purge(String principal, Optional<String> onBehalfOf, String reason, List<EntityId> entities)`, built with `Purge.by(principal).onBehalfOf(…).reason(…).entity(id)…build()`; a blank principal or reason, or an empty list, throws `IllegalArgumentException`, as other builders do for programming errors; duplicate IDs count once.
- `PurgeResult`, sealed: `Purged(PurgeRecord record)` and `Rejected(List<Problem> problems)`.
- `PurgeRecord(Basis basis, Instant transactionTime, String principal, Optional<String> onBehalfOf, String reason, List<EntityId> entities, List<RelationshipId> relationships)`, where `entities` includes the composition targets that the purge removed with the named ones.
- `Store.purge(Purge)` and `Store.purges()`, the purge records, oldest first. `Store.transactions()` keeps listing data transactions only.

### D2. Checking

Under the writer lock: every named ID must be an entity of the store (`UNKNOWN_ENTITY`, path `@purge.<n>`). The set to remove is the named entities, then the targets of their compositions, transitively, then every relationship from or to any of them. For every entity that remains and had a relationship removed, the relationship type's minimum at its end must still hold at every point in effective time where the entity is effective; otherwise `TOO_FEW_RELATIONSHIPS` with the path of that entity's ID alone, so that a caller can add it to the purge, and a hint to purge it too. Rules do not run.

### D3. Removing

- The purge takes the next basis and transaction time from the commit sequence, as a commit does.
- Removal order keeps every read sound: relationships first, then entities, so that a reader never finds a relationship whose end is missing. A view that reads while a purge runs may see part of it; once the purge returns, every view reads the purged state.
- The store keeps the purged IDs in a set that ID generation skips and that supplied IDs are checked against (`ENTITY_EXISTS`, with a message that says the ID was purged).
- Idempotency keys whose stored transaction wrote a purged entity or relationship (its receipt created or ended one, or a write targets one by ID) lose the stored transaction and are marked purged; a commit that repeats such a key is rejected with the new code `IDEMPOTENCY_KEY_PURGED`, at `@transaction.idempotencyKey`.
- The purge record is added, and the basis published. A view known at a purge's transaction time reads at the purge's basis.

### D4. Durable stores

- The purge is logged as a record of its own kind, with the purge record's fields and the removed IDs, and forced to disk.
- The purge takes the snapshot lock before the writer lock, as `close` does, so that no snapshot file of an earlier basis is written after the files are deleted. Then, under both, the store writes a snapshot file at the purge's basis, and deletes every older snapshot file and every log segment that holds records at or below that basis. A new segment starts after it.
- Recovery applies a purge record by removing the IDs it lists. If the newest valid snapshot file is older than a purge record in the log, recovery writes a snapshot file at the end of recovery and deletes the older files before the store opens, completing an interrupted purge; if the snapshot file was written but older files remain, recovery deletes them. If writing the snapshot file or deleting fails after the purge was logged, the purge stays applied, the call throws `UncheckedIOException`, the store refuses further writes, and the next opening completes it.
- A snapshot file holds the purge records, from which the purged IDs follow, and the purged idempotency keys.
- The log's format version stays 1: nothing is released yet.

### D5. Tests

- The purge scenarios run against the in-memory store, and the durable ones against a directory, searching every file for the bytes of the purged values.
- Equivalence tests compare a durable store and an in-memory twin through a purge, before and after reopening.

## Risks / Trade-offs

- [Purging rewrites every file of a durable store under the writer lock] → Purges are rare and privileged; the guide says that commits wait while a purge runs.
- [A view open during a purge may see part of it] → Removal order keeps reads sound; the guide tells applications not to rely on views across a purge.
- [Results reported before a purge cannot be reproduced after it] → The purge record says when and why history changed.
