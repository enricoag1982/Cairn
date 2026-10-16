# Design

## Context

Views traverse with `traverse(from, name, follow, maxDepth)` (`relationships` spec). Queries are data (ADR-0023): a `Start` (entity type, binding, exact type) and steps, among them `QueryStep.Traverse(from, name, binding, relationship, depth, along, minDepth, maxDepth)`, with Java builder overloads that take positional `Optional`s. Rows are `Row(Map<String, Cell>)`, with `entity`, `relationship` and `value` accessors that return `Optional`s of generic states and `Value`s. Rules record what they read (ADR-0025): a query's start lists every entity of its type, which is a read of the type.

## Goals / Non-Goals

**Goals:** the conveniences of the proposal, with no change to what existing calls do.

**Non-goals:** see the proposal.

## Decisions

### D1. Traversals in views

`View` gains two methods:

- `traverse(EntityId from, String name)`, which follows every relationship to the end;
- `traverse(EntityId from, String name, int maxDepth)`, which follows every relationship up to a depth.

The existing method stays, and a maximum depth of `Integer.MAX_VALUE` means no limit, as it does today. Breadth first, each entity visited once, so cycles end it; the reads that rules record are the same as for the existing method.

### D2. Traversals in queries

- `QueryStep.Traverse.maxDepth` becomes an `OptionalInt`: empty means to the end.
  - The record's canonical constructor changes, and a constructor with an `int` stays.
  - A minimum depth above a maximum is still an invalid query.
- A new record, `Traversal`, collects the options with withers:
  - `Traversal.of(from, name, binding)` starts one, with a minimum depth of 1 and no maximum;
  - then `minDepth(int)`, `maxDepth(int)`, `depth(String binding)`, `relationship(String binding)` and `along(Condition)`.
  - `Query.Builder.traverse(Traversal)` adds the step.
  - The existing overloads stay.
- In JSON, `maxDepth` becomes optional, and a missing value means to the end. The JSON Schema follows.

### D3. Queries from given entities

- `Start` gains `List<EntityId> ids`.
  - Empty means every entity of the type, as today.
  - A constructor without it stays.
- `Query.from(entityType, binding)` stays. `Query.Builder.ids(EntityId...)` and `ids(Collection<EntityId>)` set the IDs.
- With IDs, the start binds the entities with those IDs that are of the start type (or its subtypes, unless the exact type is asked for) and effective at the view's effective time, each once.
  - IDs of other types, unknown IDs and entities that are not effective are left out without a problem, as a filter would leave them.
  - Rows are ordered as the results requirement says, so the order of the given IDs does not matter.
- In a rule's view, such a start reads each given entity (key E of ADR-0025), not the type (key T). A change to another entity of the type no longer runs the rule again.
- In JSON, `start` gains `ids`, an array of ID strings, and the JSON Schema follows. An ID that is not a valid ID string is a problem located at it.

### D4. Typed row accessors

`Row` gains these accessors:

- `string`, `bool`, `intValue`, `longValue`, `decimal`, `date` and `instant`. Each returns `Optional` of its Java type.
- `number`, which returns an `Optional<BigDecimal>` for an int, long or decimal value.
- `<T> Optional<T> entity(String column, Function<EntityState, T> as)`, so that `row.entity("manager", Person::from)` reads a generated type.

A cell of another kind fails with `IllegalArgumentException`, which names the column, the kind found and the kind wanted, as the existing accessors do. An empty cell gives an empty `Optional`.

### D5. `INVERSE` constants

- Every generated relationship type's interface gets `String INVERSE`, its inverse name, next to `TYPE`.
- Attribute constants that would clash with `INVERSE` get the suffix `_`, as they do for `TYPE`.
- Inverse aliases get no constants.

### D6. The HR example

- **Rules.** `NoUnitCycles` and `DecidedByManager` use `view.traverse(unit, PartOf.TYPE)`.
- **Reports.** `Reports` drops `MAX_LEVELS` and uses traversals to the end, with `Traversal` for the one that binds its depth.
- **Vacation rule.** `vacation-within-entitlement` starts its query from the requester's employment with `ids`.
- **Tests.** `HrStoriesTest` reads rows with the typed accessors.
- **Inverse names.** Inverse names in rules and queries use `INVERSE` constants, where the relationship type's interface is at hand.
- **Gaps.** The HR page's gaps table loses the rows this change closes.

## Risks / Trade-offs

- **Unbounded traversals.** A traversal to the end of a large graph reads all of it; each entity is still visited once.
- **Canonical constructor.** `QueryStep.Traverse` and `Start` change their canonical constructors. Nothing is released, and the old shapes stay as constructors.
