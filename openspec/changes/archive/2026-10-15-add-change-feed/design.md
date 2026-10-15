# Design

## Context

ADR-0029 decides a change feed built from an index of what each basis changed, read from a basis or subscribed to through `java.util.concurrent.Flow`. The engine writes each commit as one batch of ledger keys (ADR-0027) from the timelines before and after each change (`Ledger.add`), keeps transaction records, schema history and purge records in basis order, and replays recovery through the same `Ledger.write`. Bases are consecutive: every commit, registration and purge takes the next one.

## Goals / Non-Goals

**Goals:** the changes of every basis, alike in every kind of store, read from a basis with a limit or through a publisher; cleaned by purges; as JSON and as an agent tool; an HR test that tells an approver of a new request.

**Non-goals:** see the proposal.

## Decisions

### D1. The API

In `cairn-api`, package `data`:

- `sealed interface Change permits TransactionChange, SchemaChange, PurgeChange`, with `basis()` and `transactionTime()`.
- `record TransactionChange(TransactionRecord transaction, List<Changed> changed)`.
- `record SchemaChange(SchemaTransaction registration)`.
- `record PurgeChange(PurgeRecord purge)`.
- `record Changed(Id id, String type, boolean created, List<EffectivePeriod> periods)`.
  - `id` is an `EntityId` or a `RelationshipId`.
  - `type` is the type's name in the schema at the change's basis.
  - `periods` are ordered, do not overlap and are not adjacent. The last one can be open.
- `changed` is ordered by ID, compared as text.

`Store` gains two methods:

- `List<Change> changes(Basis after, int limit)`. It returns the changes at the bases after `after`, up to the latest, in basis order, at most `limit` of them. `Basis.NONE` reads from the first. A limit below 1 throws `IllegalArgumentException`, and a basis after the latest gives an empty list.
- `Flow.Publisher<Change> changeFeed(Basis after)`, see D5.

Durable and RocksDB stores refuse both once closed, as they refuse every other call.

### D2. The index keys

`LedgerCodec.Kind.CHANGE`, prefix 7:

- The key is the basis (8 bytes) and the ID (length-prefixed).
- The value is:
  - whether it is a relationship;
  - the type ID;
  - whether the transaction created it;
  - the changed periods, as instants, with a mark for an open end.

`Ledger.add` writes one such key whenever the timeline before and after differ, which is exactly when it writes anything today. It computes the periods by sweeping the boundaries of both timelines. At each instant, the version is the segment that covers it, or nothing; the versions of the covering segments are compared: their values, transaction time and recorded basis, not their bounds, as `Timeline.of` merges them. Neighboring changed intervals merge. A write that leaves a period's values as they were records no new version there (`Timeline.update`), so it changes nothing there; a transaction that changes nothing has a change with an empty `changed`.

A read scans the range of the bases it needs. It resolves type IDs to names through the schema index at each basis, and sorts each basis's entries by ID text.

### D3. Records

The store merges the index with its records, by basis:

- transaction records, as `transactions()` holds them;
- the schema history, as `schema().history()` holds it;
- purge records, as `purges()` holds them.

All three are kept in basis order, in memory, by every kind of store; a RocksDB store loads them when it opens. A basis that has no record is an engine bug and throws `IllegalStateException`. `changes` reads under no lock: it reads up to the latest basis published when it starts.

### D4. Purges

`Ledger.purge` also deletes the `CHANGE` keys of what it purges. It finds them at every basis that inserted one of its history rows or superseded one, which its rows and markers give, and does so in the same batch as the rest.

The purge's own basis has a `PurgeChange`. A `TransactionChange` keeps its record after a purge, with what is left of `changed`, possibly nothing.

### D5. The publisher

Each `subscribe` gets a subscription with its own cursor, starting after `after`. `onSubscribe` is called on the subscriber's thread. Everything else runs on a virtual thread of the subscription, started on the first `request`, and is serial (Reactive Streams rule 1.3).

The thread works in a loop:

1. While there is demand, it reads `changes(cursor, min(demand, 256))` and calls `onNext` for each change, advancing the cursor.
2. When it has demand but no new change, it waits until the store publishes a basis.

The store signals publications without blocking its writer: a counter that the thread rechecks, and an unpark of each waiting subscription.

The store signals publications through a hook of the commit sequence, run under the writer lock after each publication, which only bumps the counter and unparks. A failed read ends the subscription with `onError`. Closing waits for reads in progress, never for `onNext`, so that no read reaches a closed RocksDB database.

The thread ends when one of these happens:

- `cancel`.
- `request(n)` with `n < 1`, which calls `onError(IllegalArgumentException)`, as Reactive Streams rule 3.9 asks.
- A throwing `onNext`, which cancels the subscription. The exception goes nowhere else, and commits go on.
- Closing a durable or RocksDB store, which calls `onComplete` on every open subscription after its current `onNext` returns.

A subscription made on a store that is closing gets `onComplete` at once. An in-memory store never completes its subscriptions.

### D6. Durable and RocksDB stores

- **Stores kept in a directory** keep the index in memory, like the rest of the ledger. Recovery writes it again as it applies each record and each snapshot's state, through `Ledger.write`.
- **RocksDB stores** keep the index with their other keys. `StoredMetadata.FORMAT_VERSION` becomes 2, so a store written in version 1 is refused with the message that names the version. Nothing is released, so there is no migration.

The scenario tests of `change-feed` join the conformance run against RocksDB stores.

### D7. JSON and the agent tool

`JsonResults` writes a change with these fields:

- `basis` and `transactionTime`, as other results write them.
- `kind`: `transaction`, `schema` or `purge`.
- The record's `principal`, `onBehalfOf` and `reason`.
- For a schema change, `origin` (`name`, `version`).
- For a purge, `entities` and `relationships`.
- For a transaction, `changed`: a list of `{ entity | relationship, type, created, periods }`, where each period is `{ from, until }`.

`created` is always written; fields without a value and empty lists are left out, as for other results.

`cairn_list_changes` takes:

- `after`, a basis number, 0 by default;
- `limit`, from 1 to 1,000, 100 by default.

It returns `{ changes, next, latestBasis }`, where `next` is the basis of the last change returned, or `after` if none. Bad arguments are problems at `@arguments.<name>`. It reads and so needs no approval.

### D8. The HR example

A test subscribes a notifier from the latest basis. When a change creates a `LeaveRequest`, the notifier reads the request through a view at the change's basis, finds the managers of the requester's units on the request's first day, and records a notification for each. The test commits a request and waits, with a timeout, for the notification to Cleo.

## Risks / Trade-offs

- **Index size:** one key per changed entity or relationship per basis, as ADR-0029 accepts.
- **Threads:** one virtual thread per active subscription; parked threads cost little.
- **Type names after renames:** a change names a type as it was named at its basis; consumers that compare names across a rename must follow the schema history.
