---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Make stores durable with a commit log of effects and snapshots

## Context and Problem Statement

The in-memory store loses everything when its process stops. [ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md) puts durability in a commit log, and orders the work: the in-memory store with a file-based commit log and periodic snapshots first, a key-value store such as RocksDB later.
Before data is written to disk we must decide what the log holds, how a store recovers from it, and what a crash may lose, because a format that holds data cannot be changed freely afterwards.
The change `add-durable-commit-log` raised the question.

## Decision Drivers

- A committed transaction survives a crash of the process or of the machine
- Recovery gives exactly the state that was committed, with the same bases, transaction times, IDs and history, whatever code runs at recovery
- Recovery does not depend on rules, the clock or the ID generator
- No new dependencies in the engine ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md))
- The log can later feed replicas and a change feed

## Considered Options

- A log of the effects of each commit, with snapshots
- A log of the submitted transactions, run again at recovery
- An embedded database for durability now, such as RocksDB, H2 or SQLite
- Java serialization of the whole store

## Decision Outcome

Chosen option: "A log of the effects of each commit, with snapshots", because it is the only option whose recovery repeats what was committed without running validation, rules or generators again.

- **Effects, not commands.** Each committed transaction, of data or of the schema, is appended to the log as what it did: its basis, transaction time, principal, party acted for, reason and idempotency key; for data, the transaction as submitted, the entities and relationships it created or changed with their new versions, and its receipt; for the schema, the origin, the definition and the schema identifiers it assigned. Recovery applies effects; it never validates, runs rules or asks the clock or the ID generator.
- **Stable identifiers.** Values are logged by schema identifier, and schema records carry the identifiers they assigned, so recovery does not depend on how a later version of Cairn numbers schema elements.
- **Durable before visible.** A commit is appended and forced to disk under the writer lock before its basis is published. A transaction that returned committed survives a crash; one that did not may be lost, never half applied.
- **Framing.** The log is a sequence of segment files with a format version in a header. Each record carries its length and a checksum. Recovery drops an incomplete last record, which a crash can leave, and refuses to open a log that is damaged anywhere else.
- **Snapshots.** A snapshot file holds the whole state at a basis. Recovery reads the newest valid snapshot and applies the records after it. Segments that end before the newest snapshot can be deleted.
- **One process.** A directory is held by one open store at a time, through a file lock.
- **The engine owns the format.** Logs and snapshots are binary, written by `cairn-core` without libraries. Their format has a version, and a store refuses a version it does not know.

### Consequences

- Good, because recovery gives the committed state even when rules, the clock or the ID generator behave differently after a restart, or a rule's code is missing.
- Good, because every commit is durable when it returns, and a crash never leaves half a transaction.
- Good, because the records already have the shape a change feed or a replica needs.
- Bad, because effects are bigger than commands: a write records the versions it produced.
- Bad, because forcing every commit to disk limits commits to what the disk can sync, until commits are grouped.
- Bad, because a binary format needs a tool to inspect, and every change to it needs a new format version and a reader for the old one.
- Bad, because recovery time grows with the log since the last snapshot, so snapshots have to be written.

### Confirmation

- Tests commit the reference stories to a durable store, reopen it, and compare every view and timeline with those of an in-memory store that committed the same stories.
- Tests cut the log short and damage it, and check that recovery keeps every complete record, or refuses to open.

## Pros and Cons of the Options

### A log of the submitted transactions, run again at recovery

- Good, because records are small, and the log reads like an audit trail.
- Bad, because recovery depends on running the same validation, rules, clock and ID generator: a changed rule, a missing rule or a different generator gives a different state, or rejects a committed transaction.

### An embedded database for durability now

- Good, because durability, crash recovery and compaction are solved, and well tested.
- Bad, because it adds a large dependency, with native code for RocksDB, to an engine that has one small dependency.
- Bad, because ADR-0011 orders a key-value store after the file-based log, and the in-memory store is to stay the reference.

### Java serialization of the whole store

- Good, because it is little code.
- Bad, because it ties the files to the classes of one version of Cairn, and gives no log for durability between snapshots.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-durable-commit-log`.
- Grouping commits before forcing them to disk, a change feed, and replicas build on the same log later.
- After recovery, a store runs its rules once, without judging their verdicts, to learn what they read; recovery itself still only applies effects. See [ADR-0025](0025-run-a-rule-again-when-the-data-it-read-changes.md).
