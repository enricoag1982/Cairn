# Proposal

## Why

Cairn's data model is a typed property graph (ADR-0010): most of what a system of record knows lies in the links between entities, such as who owns whom, who holds an account, and which roles a party plays.
Entities exist since `add-entity-versions`, but they cannot be linked yet.
This change adds relationships as first-class data, with their own history, and the integrity rules that must hold at every point in effective time (ADR-0008).

## What Changes

- Relationships: a write relates a source entity to a target entity with a relationship type, for an effective period, with attribute values. Relationships have IDs from the store's generator, temporary IDs, and the same patch, replace and end-date writes as entities.
- Integrity at commit, at every point in effective time:
  - ends of the right entity types
  - relationships within the lifetimes of both ends; ending an entity while its relationships continue is rejected, unless the same transaction ends them
  - multiplicities at both ends: required relationships exist, and maximums are not exceeded
  - no two relationships of one type between the same ends at the same time
- Compositions: when a composition's source ends, its targets end with it, in the same transaction.
- Symmetric relationship types: either end can be given as the source.
- Reading: a relationship and its timeline, navigation from an entity by a relationship type's name or inverse name, and recursive traversal with a condition and a depth limit.
- Relationship rules: rules can apply to relationship types, and the reference rule `total-ownership-at-most-100` runs.
- The reference domain's relationship stories become executable (RD-2 to RD-4, RD-8) and gain story RD-13.

### Non-goals

- Aggregations, such as the total balance by ultimate parent of RD-6: `add-queries-and-aggregation`.
- Changing a relationship's ends: end it and relate again.
- Indexes for navigation in the in-memory store: navigation scans.

## Capabilities

### New Capabilities

- `relationships`: relating entities, integrity at both ends over effective time, compositions, symmetric types, navigation and traversal

### Modified Capabilities

- `entity-rules`: rules on relationship types

## Impact

- **Decisions:** ADR-0008 (relationships within their ends' lifetimes), ADR-0010 (first-class directed relationships, multiplicities, composition, inverse names, symmetric types), ADR-0018 (rules) and ADR-0019 (IDs). All are accepted.
- **Code:** `cairn-api` gains `RelationshipId`, the `Relate` write, `RelationshipState`, navigation and traversal on `View`, and `RelationshipRule`; `cairn-core` stores relationships as timelines and checks their integrity.
- **Dependencies:** none.
- **Docs:** glossary (link, traversal), the reference domain (RD-13), the architecture page.
