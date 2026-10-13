---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Express queries as data: a pipeline of steps over named bindings, with a Java builder and a JSON form

## Context and Problem Statement

Views read one entity, list the entities of a type, navigate and traverse ([ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md)). Applications and agents also need to filter, join along relationships, follow ownership chains to their end, and aggregate, such as the total balance of accounts by ultimate parent (RD-6 in the [reference domain](../reference/reference-domain.md)).
The [API research note](../research/2026-10-12-graph-system-of-record-api.md#queries-and-aggregation) concluded that queries are data structures built through a typed builder, that a text language can compile to them later, and that every aggregation runs against one view.
The shape of a query is public API in Java and, for agents, in JSON ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)), so it is costly to change. The change `add-queries-and-aggregation` raised the question.

## Decision Drivers

- One query model for Java, generated types and agents, with problems instead of exceptions for bad queries
- Readable by people and agents, and checkable against a schema before it runs
- The same answer from every storage implementation, and from one view, whatever runs concurrently ([ADR-0011](0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md))
- Room for indexes, push-down and a text language later, without changing what a query means

## Considered Options

- A pipeline of steps over named bindings
- Graph patterns matched as a whole, as in Cypher and ISO GQL
- A text language first, a subset of ISO GQL
- Java streams over views

## Decision Outcome

Chosen option: "A pipeline of steps over named bindings", because each step has a simple meaning on its own, a query can be checked and explained step by step, and its JSON form is a list that agents build from a JSON Schema.

- **Rows of bindings.** A query starts from an entity type, with its subtypes unless it asks for the exact type, and binds each entity to a name. Each following step works on the rows so far: a filter keeps rows, a follow step binds the entities and relationships linked by a relationship name or inverse name, and a traversal binds those reached up to a maximum depth.
- **Then aggregation and output.** Optional grouping with aggregates (count, sum, minimum, maximum, average), then the columns to return, an order and a limit.
- **One view.** A query runs against a view and sees exactly its coordinates: an effective time, and a basis or transaction time to read as of. Rows come in an order fixed by the query and the data, never by the storage.
- **Data in `cairn-api`.** Queries are immutable records built through a builder. Generated types add typed attribute references, which build the same records ([ADR-0021](0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md)).
- **A JSON form** that mirrors the records one to one, with a published JSON Schema, read with problems located in the text, as payloads are.
- **Problems, not exceptions.** A query that names an unknown type, binding, attribute or relationship, or aggregates or compares values of the wrong type, returns problems with hints, before anything is read.
- **A text language later** compiles to the same records.

### Consequences

- Good, because a query is a value: it can be logged, compared, sent by an agent, explained, and run again at other coordinates.
- Good, because each step's meaning is local, so the engine can later reorder, index or push steps down without changing results.
- Good, because the same checks and problems serve Java callers and agents.
- Bad, because some graph patterns that Cypher states in one line, such as a cycle, take several steps and filters.
- Bad, because agents know Cypher and SQL better than a new JSON form; the JSON Schema and examples have to make up for that until a text language exists.
- Bad, because queries scan the in-memory store, so large stores need indexes, which come later.

### Confirmation

- Tests run the reference stories' queries, including RD-6 and RD-10, through the Java builder, generated types and the JSON form, and expect the same rows.
- Tests show that invalid queries return located problems and read nothing.

## Pros and Cons of the Options

### Graph patterns matched as a whole, as in Cypher and ISO GQL

- Good, because patterns state joins compactly, and people know them from graph databases.
- Bad, because a pattern's meaning depends on matching rules (which paths, which repeats) that are hard to state as data and to check step by step.
- Neutral, because a text language for patterns can still compile to a pipeline later, for the patterns that a pipeline expresses.

### A text language first, a subset of ISO GQL

- Good, because agents and people already write similar languages.
- Bad, because a parser, error locations and a grammar come before the first query runs, and the Java API would build strings.

### Java streams over views

- Good, because there is nothing new to learn for Java developers.
- Bad, because a stream is code, not data: agents cannot send it, the engine cannot check or optimize it, and nothing tells a slow query from a fast one.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-queries-and-aggregation`.
- Indexes, columnar copies for analytics, push-down to storage, and the text language are later steps of the research note's roadmap.
