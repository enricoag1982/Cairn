# Proposal

## Why

Views read one entity, list the entities of a type, navigate and traverse. Anything more, such as filtering on values, joining along relationships, finding the end of an ownership chain or totalling balances, has to be written by hand in Java, and agents cannot do it at all.
The reference domain needs it: RD-10 (polymorphic queries) and RD-6 (the total balance by ultimate parent, on two time axes).
ADR-0023 decides the form of queries: data, a pipeline of steps over named bindings, built in Java or written as JSON. This change builds it, with the typed query model that ADR-0012 and ADR-0021 promise for generated types.

## What Changes

- Queries as data in `cairn-api`, run against a view:
  - a start from an entity type, with or without its subtypes
  - filters: comparisons of attributes, IDs and types with values, presence of values, links by a relationship name, and their combinations
  - follow steps along a relationship name or inverse name, optionally keeping rows without links, binding the entity at the other end and the relationship
  - traversals up to a maximum depth
  - grouping with count, sum, minimum, maximum and average
  - the columns to return, an order and a limit
- Results as rows with named columns, in an order fixed by the query and the data, reporting the view's coordinates.
- Problems instead of exceptions for queries that do not fit the view's schema, with hints, before anything is read.
- A JSON form of queries with a published JSON Schema, read with problems located in the text, and query results as JSON.
- A tool for agents, `cairn_query`.
- Typed attribute references on generated types, to build queries in Java with compile-time types, and reading result rows as generated types.

### Non-goals

- Indexes, push-down to storage and columnar copies; queries scan the store's data at the view's coordinates.
- A text query language (ADR-0023: later, compiled to the same records).
- Subqueries, unions, and aggregates over time (such as a value's history); queries read one view.

## Capabilities

### New Capabilities

- `queries`: queries as data, their steps and their meaning, results, problems, and the JSON form

### Modified Capabilities

- `agent-tools`: the tool `cairn_query`, and the JSON Schema of queries from `cairn_json_schema`
- `java-types`: typed references and their constant names, and reading query rows as the nearest generated type

## Impact

- **Decisions:** ADR-0023 (queries as data), accepted under the standing approval with this proposal; ADR-0010 (polymorphic queries include subtypes), ADR-0013 (reads through views), ADR-0017 (agents) and ADR-0021 (generated types), all accepted.
- **Code:** a new package `query` in `cairn-api` and `View.query`; evaluation in `cairn-core`; the JSON form, its JSON Schema and results in `cairn-documents`; `cairn_query` in `cairn-agent-tools`; attribute references in `cairn-codegen`. No new dependencies.
- **Docs:** a guide for writing queries, the glossary (aggregate, binding, group key, query, query step, typed reference), the architecture page, the agent guide and the guide to generated types.
