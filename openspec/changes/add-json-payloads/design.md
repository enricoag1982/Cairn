# Design

## Context

`cairn-documents` reads and writes YAML 1.2 schema documents with SnakeYAML Engine (`add-schema-documents`); the generic API holds transactions, writes, states and problems as Java records (`add-entity-versions`, `add-relationships`, `add-entity-rules`). This change maps them to text. The requirements are in `specs/json-payloads/spec.md`.

## Goals / Non-Goals

**Goals:**

- One reader and writer for payloads, sharing the node-tree reading, strictness and locations of schema documents.
- Generated JSON Schemas that agents and editors use to build valid payloads.

**Non-Goals:**

- Transport, authentication and MCP: `add-agent-tools`.

## Decisions

### D1. API in `cairn-documents`

- `Payloads.readTransaction(String text, Schema schema)` returns a sealed `PayloadReadResult`: `Read(TransactionPayload payload)` or `Invalid(List<Problem> problems)`. `TransactionPayload(Transaction transaction, PayloadLocations locations)` with `locate(List<Problem>)`.
- `Payloads.toJson(...)` overloads for `Receipt`, `EntityState`, `RelationshipState`, lists of states, `Link`, `Step` and `Problem`.
- `JsonSchemas.values(Schema schema, String typeName)` and `JsonSchemas.transactions(Schema schema)` return JSON Schema text.

### D2. Payload format

- Top-level keys: `cairn`, `principal`, `onBehalfOf`, `reason`, `idempotencyKey`, `writes`.
- Write keys mirror the records: `create { temporaryId, id, entityType, from, until, values }`, `relate { temporaryId, id, relationshipType, source, target, from, until, values }`, `patch { target, from, until, set, clear, conflictCheck }`, `replace { target, from, until, values, conflictCheck }`, `endDate { target, from, conflictCheck }`.
- `conflictCheck` is `{ perEntity: <basis> }` or `{ perAttribute: <basis> }`; absent means none.
- References: `$name` is a temporary ID; anything else is an ID, entity or relationship, resolved by the engine.
- The type of a `patch` or `replace` target is found from the payload (a temporary ID created earlier in it) or from the schema and store: the reader takes an optional resolver from IDs to type names, which the store provides; without it, values are read by their own YAML type.

### D3. Locations

- The reader records, for every write, the pointer of the write and of each value key, and maps problem paths (`$acme.name`, `<id>.balance`, `$owns1`) to the last write concerning that reference, then to the value key if the path has an attribute.

### D4. JSON forms

- Field names follow the Java records; `Optional.empty()` and empty collections are left out; `Value`s are written as JSON scalars by type, decimals from `BigDecimal.toPlainString()`.
- Problems: `{ code, path, message, expected, found, hint, ruleCode, location: { pointer, line, column } }`.

### D5. Generated JSON Schemas

- Values schema: `type: object`, `additionalProperties: false`, a property per effective attribute; `string` with `pattern` (anchored as `^(?:...)$`), `enum`, `minLength`, `maxLength`; `integer` with 32-bit or 64-bit bounds and the range; `number` for decimals with the range (`exclusiveMinimum` and so on); `boolean`; `string` with `format: date` or `format: date-time`. Descriptions and examples are copied.
- Transactions schema: the payload structure with `$defs` per concrete entity type and relationship type; `create` and `relate` are `oneOf` per type name with `const` on the type and `$ref` to its values; `patch` and `replace` values are checked by name only against the union of attribute names, since their type is known only from the store.

## Risks / Trade-offs

- [Patch values cannot be typed by the JSON Schema alone] → The engine validates them at commit; the schema still catches unknown attribute names.
- [Java regular expressions in generated JSON Schemas] → Patterns are expected to stay in the common subset (D4 of `add-self-describing-schema`).
