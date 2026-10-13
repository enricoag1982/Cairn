# Design

## Context

ADR-0023 decides that queries are data: a pipeline of steps over named bindings, built in Java or written as JSON, run against one view, returning problems for invalid queries. Views already list the entities of a type with their subtypes, navigate by relationship name or inverse name, and traverse (`View.entities`, `navigate`, `traverse`). This design settles the records, evaluation, the JSON form, the tool and the typed references. The requirements are in the deltas `queries`, `agent-tools` and `java-types`.

## Goals / Non-Goals

**Goals:**

- One model of queries for Java, generated types and agents.
- Results that depend only on the query and the view: the same rows in the same order from every store.
- Problems with paths and hints, located in JSON text, for every invalid query.

**Non-Goals:**

- Indexes, query planning beyond step order, push-down, a text language, subqueries.

## Decisions

### D1. Records in `cairn-api`

A new exported package `io.github.enricoag1982.cairn.query`, of immutable records:

- `Query(Start start, List<QueryStep> steps, Optional<Grouping> grouping, List<Column> columns, List<Order> order, OptionalInt limit)`, built with `Query.from(entityType, binding)` and a builder: `exactType()`, `where(Condition)`, `follow(from, name, binding)`, `optionalFollow(...)`, `traverse(from, name, minDepth, maxDepth, binding)`, then `groupBy(...)`, `aggregate(...)`, `select(...)`, `orderBy(...)`, `limit(n)`, `build()`. Follow and traversal steps can also bind the relationship, and a traversal its depth, under names of their own.
- `Start(entityType, binding, exactType)`.
- `QueryStep`, sealed: `Filter(Condition)`, `Follow(from, name, binding, Optional<String> relationship, boolean optional)`, `Traverse(from, name, binding, Optional<String> relationship, Optional<String> depth, int minDepth, int maxDepth)`.
- `Condition`, sealed: `Compare(Operand left, Comparison comparison, Operand right)`, `In(Operand, List<Value>)`, `HasValue(binding, attribute)`, `HasLink(binding, name)`, `IsA(binding, entityType)`, `And(List<Condition>)`, `Or(List<Condition>)`, `Not(Condition)`.
- `Operand`, sealed: `AttributeOf(binding, attribute)`, `IdOf(binding)`, `TypeOf(binding)`, `Literal(Value)`. `Comparison`: `EQ`, `NE`, `LT`, `LE`, `GT`, `GE`. IDs and type names compare as strings; `TypeOf` is the exact type, and `IsA` includes subtypes.
- `Grouping(List<GroupKey> keys, List<Aggregate> aggregates)`; `GroupKey` is a binding or an attribute of a binding, with a column name; `Aggregate(Function function, Optional<AttributeOf> of, String name)` with `COUNT`, `SUM`, `MIN`, `MAX` and `AVG`.
- `Column`: a binding, an attribute of a binding, a depth, a group key or an aggregate, each with a name. `Order(column, descending)`.
- `QueryResult`, sealed: `Rows(Instant effectiveAt, Basis basis, List<String> columns, List<Row> rows)` and `Invalid(List<Problem> problems)`. A `Row` holds one `Cell` per column, sealed: `EntityCell(EntityState)`, `RelationshipCell(RelationshipState)`, `ValueCell(Value)`, `Empty`; it has accessors by column name.
- `View.query(Query)` returns the `QueryResult`.

### D2. Meaning

- Rows are tuples of bindings. The start gives one row per entity, from `View.entities` (or only the exact type). A follow step gives one row per `View.navigate` link; an optional one keeps a row without links, with empty bindings. A traversal gives one row per entity reached at depths from `minDepth` to `maxDepth`, at its smallest depth; depth 0 is the bound entity itself, without a relationship.
- Conditions follow the `queries` spec: a comparison with a missing value or an empty binding is false; numbers compare by value across `int`, `long` and `decimal`; strings compare by code point; booleans compare for equality only. A literal takes the value type of the attribute it is compared with, where it can.
- Aggregates: `COUNT` without an attribute counts rows, with one counts values; `SUM` is an exact decimal; `AVG` is a decimal rounded to 34 significant digits (`MathContext.DECIMAL128`); `MIN` and `MAX` keep the attribute's value type; sums and averages need numbers, minimums and maximums any ordered type.
- Order: the query's order, then the bindings' IDs in binding order, with missing values first when ascending; for grouped rows, the group keys take the place of the bindings. Then the limit.
- Checking comes first: every name is resolved against the view's schema (types, attributes including inherited and trait attributes, relationship names and inverse names, by name or alias, without regard to case) and against the bindings made by earlier steps. Problems use the codes `UNKNOWN_ENTITY_TYPE`, `UNKNOWN_ATTRIBUTE` and `UNKNOWN_RELATIONSHIP_TYPE`, which exist, and new codes `UNKNOWN_BINDING`, `DUPLICATE_BINDING`, `INCOMPARABLE_VALUES` and `INVALID_AGGREGATE`, with explanations. Paths are `@query.from`, `@query.steps.<n>`, `@query.groupBy.<n>`, `@query.aggregates.<n>`, `@query.columns.<n>` and `@query.order.<n>`, counted from 0, with a closest-name hint where one is close.

### D3. Evaluation in `cairn-core`

The view's implementation checks the query, then evaluates the steps in order over lists of rows, with the view's own reads. Nothing is cached between queries. The evaluator is internal.

### D4. JSON form in `cairn-documents`

- `Queries.read(text)` reads JSON or YAML 1.2 strictly, as payloads are read, into a `Query` with the locations of its parts, or into located problems (`UNKNOWN_KEY` with the closest key, `MISSING_KEY`, `INVALID_VALUE`, `DOCUMENT_SYNTAX`). A located query can locate the problems that running it returns, by their `@query…` paths.
- The form mirrors D1: `from: { type, as, exact? }`; `steps`, each an object with one key: `where`, `follow`, `optionalFollow` or `traverse`; conditions with one key: `compare: { left, op, right }`, `in`, `hasValue`, `hasLink`, `isA`, `and`, `or`, `not`; operands `{ attribute, of }`, `{ id: of }`, `{ type: of }` or `{ value }`; then `groupBy`, `aggregates`, `columns`, `order` and `limit`.
- A published JSON Schema of the form, `query-1.schema.json`, kept in step with the reader by a test as the schema document's is.
- `JsonResults` writes a `QueryResult`: `{ effectiveAt, basis, columns, rows }`, each row a list of cells in the forms of `json-payloads` (entity and relationship states, values), or `null` for an empty cell.

### D5. The tool

`cairn_query { query, effectiveAt, knownAt? }` in `cairn-agent-tools`: `query` is text; problems of reading and of the query are located in it. The result is `{ effectiveAt, basis, result }` like the other reads. `cairn_json_schema` gains the kind `query`.

### D6. Typed references

- `cairn-api` gains typed attribute references in package `query`, such as `AttributeRef<T>`, with `of(binding)` giving an operand that builds conditions with values of `T` (`eq`, `ne`, `in`, `hasValue`, and `lt`, `le`, `gt`, `ge` for ordered types), and that serves as a column, a group key, an order and the argument of an aggregate.
- The generator adds to each generated interface one constant per attribute it declares, in upper snake case (`birthDate` becomes `BIRTH_DATE`), with the name rules of the `java-types` spec for clashes. `Row` gains `entity(column)` and `relationship(column)`, which generated types read with `from`.

## Risks / Trade-offs

- [Queries scan] → Fine for an in-memory store; indexes come with the key-value store, and do not change results.
- [A traversal's maximum depth can cut a chain short, so a filter on "no further link" may keep a party that is not the end] → The caller chooses the depth; the guide says so. A step that fails when the depth is reached is a later option.
- [Rows multiply along links: an account held by a party with two ultimate parents counts in both groups] → That is the meaning of joining; the guide shows it.
