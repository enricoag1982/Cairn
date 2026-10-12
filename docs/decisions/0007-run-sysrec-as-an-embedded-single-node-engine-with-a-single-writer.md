---
status: proposed
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Run sysRec as an embedded single-node engine with a single writer

## Context and Problem Statement

sysRec is a primitive for defining and keeping enterprise data: typed entities and relationships, with history on two time axes, consistent transactions, traversals and aggregations.
The exploration is in the [API research note](../research/2026-10-12-graph-system-of-record-api.md).
Before designing the API we need to know where the engine runs and how writes are ordered.
That decides whether the API is an in-process interface or a wire protocol, and how much the consistency model has to cover.

## Decision Drivers

- Strong consistency: atomic writes across several entities, serializable isolation, constraints checked at commit, repeatable reads
- The same behavior for every storage implementation, including the in-memory one ([ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md))
- Fast reads from data kept on the JVM heap
- Simple to reason about, test and operate in the first versions
- The expected workload: enterprise reference data, with modest write rates and many reads

## Considered Options

- Embedded engine on one node, with a single writer and snapshot readers
- Shared server from the start, with one writer node
- Distributed engine with partitioned writers

## Decision Outcome

Proposed option: "Embedded engine on one node, with a single writer and snapshot readers", because it gives the strongest consistency for the least machinery, and keeps the other options open.

- The engine is a library that runs inside the application's JVM.
- Every commit goes through one writer. It validates the transaction, appends it to the commit log and publishes a new snapshot. Transactions are therefore serializable without locks.
- Any number of readers work on immutable snapshots at the same time ([ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md)).
- The API is a synchronous Java interface. Large results are streamed through closeable iterators. Virtual threads make blocking calls cheap.
- Every command, query and result is a plain, serializable value. A server can then be added later as an adapter over the same API, without changing behavior.

### Consequences

- Good, because serializability, constraint checks and repeatable reads follow from the design instead of from distributed protocols.
- Good, because the API is an ordinary Java interface that is easy to use and to test.
- Good, because a server, and read replicas fed from the commit log, can be added later.
- Bad, because write throughput is limited to what one writer can commit: thousands of transactions per second, more with group commit. Workloads beyond that need partitioning, which this decision does not cover.
- Bad, because each application that embeds the engine owns its data. Sharing one store between applications needs the future server.
- Bad, because the JVM's limits (heap size, garbage-collection pauses) become the engine's limits.

### Confirmation

- The conformance suite includes concurrent transactions and checks that the outcome equals some serial order.
- The public API exposes no locks and no threading concepts.

## Pros and Cons of the Options

### Shared server from the start

- Good, because several applications can share one store from day one.
- Bad, because a wire protocol, authentication, deployment and operations come before the data model is proven.

### Distributed engine with partitioned writers

- Good, because write throughput grows with the number of nodes.
- Bad, because transactions across partitions need distributed commit, and every consistency guarantee gets more expensive.
- Bad, because nothing known about the workload needs it yet.

## More Information

- Revisit when a real workload needs more write throughput than one writer gives, or when several applications must share one store.
