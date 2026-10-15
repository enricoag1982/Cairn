---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Hide storage behind an ordered key-value store and a commit log

## Context and Problem Statement

The API must not depend on how data is stored: in memory, in an embedded key-value store on disk, in a hybrid of heap and files, or one day in a distributed store.
Hiding the storage behind an interface is easy; making every implementation behave the same is the hard part.
We need to decide what a storage implementation must provide, where the logic for time, the graph and inheritance lives, and how we prove that implementations are interchangeable.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Identical behavior for every storage implementation
- New storage implementations are cheap to write
- Fast, deterministic tests
- Room for a hybrid of heap and disk, and later for a distributed store

## Considered Options

- A narrow storage interface (an ordered key-value store plus a commit log), with all model logic in a core engine
- A rich storage interface, where each backend implements the bitemporal graph natively
- A relational database as the main backend, using SQL:2011 temporal features

## Decision Outcome

Chosen option: "A narrow storage interface with all model logic in a core engine", because it gives identical behavior by construction and keeps backends small.

```text
API      schema API, data API (views, transactions, traversal, queries)
Core     meta-model, versions on two time axes, constraints, index encoding, query planning
SPI      ordered key-value store + commit log
Backends in-memory | RocksDB | hybrid heap and disk | FoundationDB (later, if ever)
```

- The **ordered key-value store** provides get, range scan, atomic batch write and a snapshot at a version.
- The **commit log** provides append and read from a position. It gives durability and recovery, and can later feed replicas and a change feed.
- The core engine encodes everything as keys and values: entity and relationship versions, the reverse index for relationships, type indexes and the schema. Backends know nothing about the model.
- The **in-memory implementation is the reference implementation**. It defines correct behavior, serves as the test double for applications, and is a production option for data that fits in memory when combined with a durable commit log.
- A **conformance suite**, built from the OpenSpec scenarios, runs against every implementation. An implementation is valid only if it passes.
- Order of implementations: in-memory with a file-based commit log and periodic snapshots first, then RocksDB. A custom split between heap (current state) and disk (history) is built only if measurements show RocksDB is not enough.
- Backends may later offer optional push-down features (for example range filters), declared as storage capabilities, discovered at runtime and never required for correctness.

### Consequences

- Good, because time, graph and inheritance logic exists once, so implementations cannot drift apart.
- Good, because a new backend only has to provide a small, well-tested interface.
- Good, because tests run against the in-memory implementation, fast and deterministic.
- Bad, because backends with native features (a relational database, XTDB) are used only as key-value stores, which wastes those features.
- Bad, because query performance depends on the core engine's index encoding and planning, which we must build.

### Confirmation

Every storage implementation runs the full conformance suite in CI.

## Pros and Cons of the Options

### Rich storage interface

- Good, because each backend can use its native features for speed.
- Bad, because every backend reimplements the semantics of time and the graph, and behavior drifts.

### Relational database with SQL:2011 temporal features

- Good, because of mature tooling and native period types.
- Bad, because it ties the engine to one external server, against [ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md), and graph traversals map poorly to SQL.

## More Information

- Prior art for the key layout: Datomic's four indexes. An LSM tree, as in RocksDB, already keeps recent data in memory and older data on disk.
