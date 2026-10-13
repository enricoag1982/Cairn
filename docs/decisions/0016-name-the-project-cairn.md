---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Name the project Cairn

## Context and Problem Statement

The project started under the working name sysRec, short for "system of record".
That is a generic category term, hard to search for, and easily read as "RecSys", the usual abbreviation for recommender systems.
The name also fixes the Java packages, module names and publishing group, so it should be settled before anything is published.

## Decision Drivers

- Short, easy to say and spell, and searchable
- Not already used by a known project in the database or JVM space
- Usable as a lowercase Java package and module name, without digits or hyphens
- Suggests the core idea: a record whose history only grows, with links between records

## Considered Options

- Cairn
- Annal
- Palimpsest
- Keep sysRec
- Varve, Vellum

## Decision Outcome

Chosen option: "Cairn", because it meets every driver. A cairn is a pile of stones that walkers build up, one stone at a time, to mark the way: history that only grows, and links that show the path.

- The GitHub repository is `enricoag1982/cairn`.
- The publishing group and base Java package are `io.github.enricoag1982.cairn`; the Java modules are `io.github.enricoag1982.cairn` and `io.github.enricoag1982.cairn.core`.
- The Gradle project is `cairn`, with the subprojects `cairn-api` and `cairn-core`, the convention plugins `cairn.java-library` and `cairn.reference-domain-tests`, and the property `cairn.javaVersion`.
- Accepted and rejected ADRs and dated research notes keep the name sysRec, as written at the time.

### Consequences

- Good, because the name is distinctive and searchable, and the publishing group is settled.
- Good, because the rename happened before anything was published, so it broke nothing outside this repository.
- Bad, because older records and links still use sysRec. This ADR explains the former name.

## Pros and Cons of the Options

### Annal

- Good, because annals are the year-by-year record of events.
- Bad, because spoken aloud it sounds close to an unfortunate English word.

### Palimpsest

- Good, because a manuscript written over, with the earlier text still visible, matches corrections that keep what was known before.
- Bad, because it is long and hard to spell.

### Keep sysRec

- Good, because nothing has to change.
- Bad, because of every reason in the problem statement.

### Varve, Vellum

- Bad, because both are already used nearby: VarveDB is an embedded append-only event store, and Vellum is a well-known AI platform.

## More Information

- A search on 2026-10-13 found no known project named Cairn in the database or JVM space, apart from a small Rust crate, `cairn-knowledge-graph`. It was not a trademark clearance.
