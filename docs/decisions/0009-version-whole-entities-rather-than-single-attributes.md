---
status: proposed
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Version whole entities rather than single attributes

## Context and Problem Statement

[ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md) records history on two time axes.
We still have to decide what one version is: a whole entity, a single attribute, or a group of attributes.
This shapes storage, writes, conflict detection and schema evolution, and is costly to change once data exists.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Simple to implement, explain and debug
- Attributes that belong together stay consistent
- Partial updates and corrections in the past are cheap enough
- Old versions stay readable after the schema evolves
- Aggregations over many entities stay possible

## Considered Options

- A: one version per entity (row versions)
- B: one version per attribute (facts)
- C: one version per group of attributes

## Decision Outcome

Proposed option: "A: one version per entity", because it is the simplest model to build and to reason about, and its costs can be contained.

An **entity version** is the complete state of one entity for one effective period, as recorded by one transaction.
Relationships are versioned the same way.
Every attribute is versioned with its entity, so attributes carry no "temporal" flag of their own.

The costs of A are contained like this:

- **Modeling rule:** data that changes often, or comes from another source (balances, prices, ratings), goes into its own entity or relationship. Versions then stay small and stable.
- **Conflict detection per attribute:** the single writer ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)) checks whether the attributes a write changes were modified since the caller's basis. Two writers that change different attributes of one entity do not conflict.
- **Evolvable encoding:** each stored field carries the attribute's identifier, as in Protobuf. Versions written under an older schema read correctly under a newer one, without rewriting history.
- **Append-only storage:** each write adds versions for an effective period; where versions overlap, the most recently recorded one wins. Nothing is updated in place.
- **Aggregations:** scanning whole versions is fast enough on the heap for millions of entities. Columnar copies, each tied to a basis, can be added later as an optimization.

The API returns whole entities whatever the storage choice. Moving a single type to per-attribute or grouped storage later is an internal change, not an API break.

### Consequences

- Good, because one stored record per version is easy to store, read, debug and explain.
- Good, because attributes that belong together can never drift apart.
- Good, because "how did this entity look over time?" is answered directly by its versions.
- Bad, because changing one attribute copies the whole entity, and a correction in the past splits every overlapping version.
- Bad, because aggregating one attribute reads whole versions.
- Bad, because the modeling rule above must be taught and reviewed.

### Confirmation

- Benchmarks on the reference domain track the number and size of versions per entity.
- Conformance scenarios cover partial updates by concurrent writers to different attributes.

## Pros and Cons of the Options

### B: one version per attribute

- Good, because a write stores only what changed, and each attribute has its own timeline.
- Good, because scanning one attribute across many entities works like reading a column.
- Bad, because attributes that must change together can drift apart unless modeled as one value.
- Bad, because rebuilding an entity, or its history, means merging many timelines.

### C: one version per group of attributes

- Good, because it balances A and B, like the "satellites" of Data Vault.
- Bad, because every type needs a grouping decision, and the model gets one more concept.

## More Information

- Prior art: Reladomo, XTDB and SQL:2011 period tables use A. Datomic and anchor modeling use B. Data Vault uses C.
- Revisit if benchmarks show that version volume or write cost is too high for some types.
