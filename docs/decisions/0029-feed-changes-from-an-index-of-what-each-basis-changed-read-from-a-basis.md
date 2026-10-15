---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Feed changes from an index of what each basis changed, read from a basis

## Context and Problem Statement

Side effects never run inside the engine; they belong to applications, fed by a change feed ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)). The [HR domain](../reference/hr-domain.md) shows the need: an approver learns of a waiting leave request only by polling. [ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md) and [ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md) expected the feed to read the commit log, but in-memory stores have none, and a RocksDB store's write-ahead log is not kept once flushed ([ADR-0028](0028-keep-a-store-in-rocksdb-with-its-metadata-as-keys-and-its-write-ahead-log-as-the-commit-log.md)).
A feed needs to decide what a change says, where it comes from in every kind of store, how consumers read it and resume after a restart, and what a purge does to it. Applications build on that shape, and stores keep its keys, so it is costly to change. The change `add-change-feed` raised it.

## Decision Drivers

- The same feed from every kind of store, built by the engine, as the scenario tests demand ([ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md))
- A consumer resumes where it stopped, after its own restart or the store's, without missing or repeating a change
- A slow consumer never slows commits, and the store keeps no buffer per consumer
- A purge leaves nothing in the feed that names what it purged ([ADR-0024](0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md))
- History on two time axes stays readable through views, which say everything about values ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md), [ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md))

## Considered Options

- An index of what each basis changed, written with each commit, read in basis order from any basis, with values read through views
- A feed that carries the values, before and after, of every change
- Listeners that the store calls after each commit

## Decision Outcome

Chosen option: "An index of what each basis changed, read in basis order from any basis", because it is the only option that every kind of store keeps alike, that a purge can clean, and that a consumer can resume from a basis it remembers.

- **What a change is.** One per basis, in basis order, without gaps: a data transaction with its transaction record and what it changed, a schema registration with its schema transaction, or a purge with its purge record. Dry runs and rejected transactions use no basis and leave no change.
- **What a data transaction changed.** Each entity and relationship whose timeline differs at the transaction's basis from the basis before: its ID, its type, whether the transaction created it, and the effective periods in which a view at the basis and a view at the basis before read different versions of it, absent included. A write that leaves a period's values as they were records no new version there, so it changes nothing there. Composition cascades are changes like any other.
- **No values.** A consumer reads values through views at the change's basis and the basis before, as of any effective time in the changed periods. Views at old bases stay readable ([ADR-0027](0027-encode-bitemporal-data-as-keys-over-an-ordered-key-value-interface.md)), so the feed needs no copy of the data.
- **Where it lives.** Keys of the ledger, one per changed entity or relationship, ordered by basis and then ID, written in the same batch as the commit; the records come from the store's transaction records, schema history and purge records. Stores in memory, in a directory and in RocksDB keep them alike, and recovery writes them again as it applies each transaction.
- **Reading.** A consumer reads the changes after a basis it names, up to a limit, or subscribes from a basis through `java.util.concurrent.Flow`. A subscription reads the index at the consumer's pace, as it requests changes, on a thread of the store's, not the committer's, and waits for the next commit when it has delivered all; closing the store completes it. The consumer keeps the last basis it handled.
- **Purges.** A purge deletes the keys of what it purges, so a change read afterwards no longer names it, and adds a change of its own with its purge record. A change already delivered stays with the consumer, who checks the IDs it holds against the store ([ADR-0024](0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md)).
- **Agents** read the feed through a tool that takes a basis and a limit and returns the changes as JSON, with the basis to continue from ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)).

### Consequences

- Good, because the feed is the same in every kind of store, written in the commit's own batch, so it cannot miss a commit or include one that failed.
- Good, because consumers resume from a basis, at their own pace, and the store keeps nothing per consumer.
- Good, because a purge cleans the feed with the rest of the data, and views give values with the full precision of two time axes.
- Bad, because a consumer that needs values reads views, two per change, instead of finding them in the change.
- Bad, because the index grows with every change of every entity and relationship, as history rows do.
- Bad, because a future-dated fact that becomes effective causes no change ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)).
- Bad, because a RocksDB store written before this decision lacks the index; nothing is released, so the store's format version changes and older stores are refused.

### Confirmation

- Scenario tests of the reference domain read the changes of every story, through the list and a subscription, against every kind of store, before and after a purge and a reopening.
- The HR example tells an approver of a waiting leave request through a subscription.

## Pros and Cons of the Options

### A feed that carries the values

- Good, because a consumer needs nothing but the change.
- Bad, because the feed then copies every version of the data, and a purge must rewrite it.
- Bad, because a timeline change on two time axes has no single "before" and "after" value.

### Listeners that the store calls after each commit

- Good, because it is the smallest API.
- Bad, because a slow or failing listener slows or breaks commits, or the store must buffer for it.
- Bad, because a consumer that was down misses changes.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-change-feed`.
- This decision replaces, for the change feed, what ADR-0011 and ADR-0022 expected of the commit log; both stay in force for the rest and refer to it.
- Signals when future-dated facts become effective, filters by type, and feeds for replicas can follow.
- Revised on 2026-10-15, before `add-change-feed` was archived, after building it: a write that leaves the values of a period as they were records no new version there, as the engine has always done, so it is no change; the decision said the opposite before this revision. A transaction that changes nothing still has its change, with its record and nothing listed. Versions are compared by their values and the transaction time and basis that recorded them, not by the bounds of their segments.
