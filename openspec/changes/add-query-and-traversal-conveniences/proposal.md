# Proposal

## Why

The HR example found friction in reading data that rules and reports pay for on every use: traversals need a maximum depth and a condition even when there is none, so code passes `Integer.MAX_VALUE` and `relationship -> true`, and reports cut the unit hierarchy at 20 levels without notice; a traversal that binds its depth needs positional `Optional` arguments; result cells need casts to `Value` subtypes; inverse names such as `subunits` are strings; and a query always starts from every entity of its type, so a rule that queries from one employment reads every employment and runs again whenever any of them changes (ADR-0025).

## What Changes

- `View.traverse` without a condition, and without a maximum depth, which traverses to the end; cycles still end it, as each entity is visited once.
- Query traversal steps without a maximum depth, and a `Traversal` value with named options (minimum and maximum depth, depth and relationship bindings, a condition) for the Java builder.
- Queries that start from given entities: the entities with the given IDs that are of the start type and effective at the view's effective time. In rules, such a query reads only those entities.
- Typed accessors on result rows: `string`, `bool`, `intValue`, `longValue`, `decimal`, `date`, `instant`, `number` for any numeric value, and `entity(column, as)` that reads a binding as a generated type.
- Generated relationship types gain the constant `INVERSE`, their inverse name.
- The JSON form of queries mirrors the new options; the HR example uses them.

### Non-goals

- Queries over a period of effective time, and listing the instants at which what a rule read changes.
- Queries that start from unique keys.
- The previous version in the rule context, which follows in its own change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `relationships`: traversals without a condition or a maximum depth.
- `queries`: queries from given entities, traversals to the end, typed row accessors, and their JSON form.
- `java-types`: the `INVERSE` constant.
- `agent-tools`: `cairn_traverse` without a maximum depth.

## Impact

- **Decisions:** none new. Starting from given entities extends the start of a query that ADR-0023 decides without changing it; ADR-0023 gets a More Information line. It relies on ADR-0025 for what rules read.
- **Code:** `cairn-api` (`View`, `Query`, `Start`, `QueryStep.Traverse`, `Traversal`, `Row`); `cairn-core` (traversals and query execution, reads of rules); `cairn-documents` (the query JSON form and its JSON Schema); `cairn-codegen` (`INVERSE`); `cairn-example-hr` (rules, reports and tests).
- **Docs:** the query guide, the codegen guide, the HR domain page (gaps), and the architecture page.
