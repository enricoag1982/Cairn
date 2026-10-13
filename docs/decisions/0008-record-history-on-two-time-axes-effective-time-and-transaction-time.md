---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Record history on two time axes: effective time and transaction time

## Context and Problem Statement

A system of record must answer two different questions: "what was true on date X?" and "what did we know on date Y?".
Enterprises need both to reproduce a report exactly as it was produced, even after later corrections, and to record changes that take effect in the future.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Any past answer can be reproduced exactly
- Corrections never destroy what was known before
- Changes can be recorded before they take effect
- One representation of time inside the engine, convenient forms at the edges
- Integrity rules that hold over the whole history, not only today

## Considered Options

- Two time axes (bitemporal): effective time and transaction time
- Transaction time only (an audit history of the current state)
- Effective time only (corrections overwrite the past)
- Event sourcing, with state always derived from stored events

## Decision Outcome

Chosen option: "Two time axes", because it is the only option that answers both questions directly.

- **Effective time** is when a fact is true in the real world. The writer sets it, in the past or in the future.
- **Transaction time** is when Cairn recorded the fact. The engine sets it at commit; callers never set it. It only increases.
- These two names are used everywhere. Synonyms (valid time, system time, business time, processing time) are not used.
- Both axes are UTC instants with microsecond precision. Periods are half-open, `[from, to)`. An open end means "until further notice".
- An entity type can declare day granularity. Its effective periods then start and end at midnight in a configured business time zone, and callers can pass plain dates.
- Each commit gets a **basis**: a transaction number that identifies the snapshot it produced.
- A read has two coordinates: *effective at* and *known at* (a transaction time or a basis). They default to "now" and "the latest basis".
- Recorded facts are never changed or deleted. Corrections and future-dated changes use the same write: "set these values for this effective period". Where periods overlap, the most recently recorded write wins.
- Integrity rules hold at every point in effective time. In particular, a relationship's effective period must lie within the effective periods of both entities it connects. A transaction that would break this is rejected, unless it also ends or moves the affected relationships.
- The only exception to immutability is **purge**: a privileged, audited operation that removes chosen facts from all of history, for legal erasure. It leaves a record that a purge happened, not what was purged. It is designed now and built later.

### Consequences

- Good, because any answer can be reproduced from its two coordinates, and every result reports them.
- Good, because corrections and future-dated changes need no special operations.
- Bad, because every read has two coordinates. Defaults hide this for simple cases.
- Bad, because storage grows with history, except for purge.
- Bad, because constraints must be checked over whole timelines, which is harder than checking the current state.
- Bad, because nothing happens when a future-dated fact becomes effective. No transaction runs, so the change feed stays silent. Signalling that moment needs a separate feature.

### Confirmation

Conformance scenarios cover reads at past and future effective times, reads as known at earlier transaction times, corrections, future-dated changes and rejected integrity violations.

## Pros and Cons of the Options

### Transaction time only

- Good, because it is simple and gives a full audit trail.
- Bad, because it cannot record a change before it takes effect, and cannot separate "corrected" from "changed".

### Effective time only

- Good, because it is simple and answers "what was true on date X?".
- Bad, because a correction overwrites what was known, so earlier reports cannot be reproduced.

### Event sourcing

- Good, because the stored events are the complete history.
- Bad, because every read needs a derived state, and querying past states on both axes still needs the same model, built on top.

## More Information

- Prior art: SQL:2011 temporal tables, XTDB, Reladomo, Martin Fowler's "Bitemporal History", and Snodgrass's "Developing Time-Oriented Database Applications in SQL". Links are in the research note.
- [ADR-0009](0009-version-whole-entities-rather-than-single-attributes.md) decides how versions are stored on these two axes.
