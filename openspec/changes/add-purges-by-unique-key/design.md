# Design

## Context

ADR-0033 decides this change: a purge names entities by unique key too; a key without an effective time resolves over all of effective time, at the latest basis, to the one entity that ever held the value; one with an effective time resolves as a view finds it; ambiguous keys are refused.

A purge is checked by `PurgeCheck` under the writer lock, against the latest state of the store. Durable stores log the purge record and digests of the removed IDs, never the `Purge` request (`RecordCodec`, `PurgeCodec`), so a key never reaches their files. Keys in writes are checked by `UniqueKeys.check` and resolved by `Workspace.resolveKey` through `Ledger.uniqueHolders`.

## Goals / Non-Goals

**Goals:** purges that name entities by unique key, on the same checks as keys in writes, and that never remove an entity the caller did not mean.

**Non-goals:** see the proposal.

## Decisions

### D1. The API

- **`PurgeKey`.** A new record `PurgeKey(UniqueKey key, Optional<Instant> effectiveAt)` in `io.github.enricoag1982.cairn.data`, with null checks.
- **`Purge`.** `Purge` gains a component `List<PurgeKey> keys`, after `entities`, copied by the constructor. A purge needs at least one entity or key; the message for none stays the same in substance. A constructor with the four components of before stays, with no keys, so existing callers compile.
- **The builder.** `Purge.Builder` gains `entity(UniqueKey key)` and `entity(UniqueKey key, Instant effectiveAt)`, which add to `keys` in the order of the calls.
- **The result.** `PurgeResult.Purged` gains `List<ResolvedKey> keys`, after `relationships`: one `ResolvedKey(PurgeKey key, EntityId entity)` per key of the purge, in the order of the purge's keys. `ResolvedKey` is a record nested in `PurgeResult`, with null checks. A constructor with the three components of before stays, with no keys.
- **Order of the removed entities.** `Purged.entities` lists the entities named by ID, in the order given, then those named by keys, in the order of the keys, without repeats, and then the targets of compositions, as before.

### D2. Resolving keys

`PurgeCheck` resolves the keys before it does anything else, against the schema and the ledger at the latest basis:

- **The check.** Each key is checked with `UniqueKeys.check`, as keys in writes are. A key that does not pass is `INVALID_UNIQUE_KEY`.
- **With an effective time.** The key names the entity of the key's type, or of a subtype, whose timeline holds a version with the value at that time, as `Workspace.resolveKey` finds it. None is `UNIQUE_KEY_NOT_FOUND`.
- **Without one.** The key names every entity of the key's type, or of a subtype, whose timeline at the latest basis has a version with the value. One such entity is the one; none is `UNIQUE_KEY_NOT_FOUND`; several are `UNIQUE_KEY_AMBIGUOUS`.
- **Paths.** Problems about a key are at `@purge.key.<n>`, where `n` is the position of the key in `keys`, from 0. Problems about IDs stay at `@purge.<n>`.
- **The ambiguous problem.** Its message names each candidate by ID with the periods in which it held the value, in the order of the periods, adjacent periods merged; its hint says to give the key an effective time, or to name the entity by ID.
- **After resolution.** Entities named by keys join those named by ID, without repeats, and the purge goes on as before. Every problem is reported, with those of IDs and minimums; if a key does not resolve, the minimums are still checked for what does.

### D3. What is kept

Nothing new is stored: the purge record keeps numbers, and durable stores log the record and digests. The key and its value are in the request and the result only. A test proves that no file of a durable store kept in a directory, or of a RocksDB store, holds the value after a purge by key.

### D4. The new problem code

`ProblemCode.UNIQUE_KEY_AMBIGUOUS`, next to the other key codes, with a description: "A unique key in a purge, without an effective time, names several entities, which held the value at different times: give the key an effective time, or name the entity by ID."

### D5. HR-12

The erasure names Gus's employment by its employee number, `Employment.byEmployeeNumber("E-007")`, and his person by ID: a person has no unique attribute. The refused first attempt stays by ID.

## Risks / Trade-offs

- **The in-memory store scans the holders of a value,** as it does for writes. Purges are rare, so this is acceptable until storage has indexes.
- **Two ways of resolving a key.** Writes resolve at the start of the write's period, purges over all of effective time. The Javadoc of `PurgeKey` and of `Purge.Builder.entity(UniqueKey)` says so.
