# Design

## Context

`cairn-documents` reads and writes YAML 1.2 schema documents with SnakeYAML Engine (`add-schema-documents`); the generic API holds transactions, writes, states and problems as Java records (`add-entity-versions`, `add-relationships`, `add-entity-rules`). This change maps them to text. The requirements are in `specs/json-payloads/spec.md`.

## Goals / Non-Goals

**Goals:**

- One reader and writer for payloads, sharing the node-tree reading, strictness and locations of schema documents. That needs a small refactoring first: the reader's helpers for typed properties and its element paths are specific to schema documents today.
- Generated JSON Schemas that agents and editors use to build valid payloads.

**Non-Goals:**

- Transport, authentication and MCP: `add-agent-tools`.

## Decisions

### D1. API in `cairn-documents`

- `Payloads.readTransaction(String text, Schema schema, IdLookup lookup)` (and an overload without a lookup) returns a sealed `PayloadReadResult`: `Read(TransactionPayload payload)` or `Invalid(List<Problem> problems)`. `TransactionPayload(Transaction transaction, PayloadLocations locations)` with `locate(List<Problem>)`.
- `Payloads.toJson(...)` for one `Receipt`, `EntityState`, `RelationshipState`, `Link`, `Step` or `Problem`, and `entityStatesToJson`, `relationshipStatesToJson`, `linksToJson`, `stepsToJson` and `problemsToJson` for lists (their element types erase to the same `List`). Output is two-space indented JSON, keys in record order, periods as `{ from, until }`; a state's `values` is always written, as `{}` when empty. `PayloadLocations` also offers `root()`, `write(n)`, `find(path)`, `of(path)` and `locate(Problem)`; `IdLookup` has `none()`, `Known` and `Kind`.
- ADR-0017's commitment 5 (payloads checked against a JSON Schema the engine generates) is met by `cairn-documents` generating the schema for tools and editors, while the engine's own validation at commit remains the check that counts.
- `JsonSchemas.values(Schema schema, String typeName)` and `JsonSchemas.transactions(Schema schema)` return JSON Schema text.

### D2. Payload format

- Top-level keys: `cairn`, `principal`, `onBehalfOf`, `reason`, `idempotencyKey`, `writes`.
- Write keys mirror the records: `create { temporaryId, id, entityType, from, until, values }`, `relate { temporaryId, id, relationshipType, source, target, from, until, values }`, `patch { target, from, until, set, clear, conflictCheck }`, `replace { target, from, until, values, conflictCheck }`, `endDate { target, from, conflictCheck }`.
- `conflictCheck` is `{ perEntity: <basis> }` or `{ perAttribute: <basis> }`; absent means none.
- References: `$name` is a temporary ID; anything else is an ID. Because `Ref` is typed, the reader decides whether an ID names an entity or a relationship with a lookup the caller passes, which a store's latest view provides through timelines (`view.timeline(id)` or `view.relationshipTimeline(id)` being non-empty), so that ended, past and future-dated subjects are found too. An ID that names neither is read as an entity ID, so that the engine reports it as unknown.
- The type of a `patch` or `replace` target is found from the payload (a temporary ID created earlier in it) or from the store: `IdLookup` answers, for an ID, whether it names an entity or a relationship and its type name; `IdLookup.from(View)` uses the view's timelines, so ended, past and future-dated subjects are found. Without a lookup, values are read by their own YAML type.
- Required keys: `cairn`, `principal` and `writes`; `create` needs `entityType` and `from`; `relate` needs `relationshipType`, `source`, `target` and `from`; `patch` and `endDate` need `target` and `from`; `replace` also needs `values`, so a forgotten `values` cannot wipe a state. A `temporaryId` may be written with or without its `$`; references must start with `$` to be temporary IDs.
- Reading problems reuse the document problem codes, with the engine's path forms (`@transaction`, `@write.<n>`, `@write.<n>.<attribute>`); an unknown write kind is `UNKNOWN_KEY` at the write, whose hint lists the kinds, closest first.
- The strict node reading of schema documents is extracted into `NodeReader`, which both readers use.

### D3. Locations

- The reader records, for every write, the pointer of the write and of each value key, and maps problem paths (`$acme.name`, `<id>.balance`, `$owns1`) to the last write concerning that reference, then to the value key if the path has an attribute. `@write.<n>` and `@write.<n>.<attribute>` map to `/writes/<n>` and its value key; `@transaction` maps to the payload's root, and `@transaction.<key>` to that top-level key. Within the last write about a subject, the search for an attribute stops at a create, relate or replace that lacks it, because there the attribute is missing. A multiplicity path (`$a3[HeldBy]`, `<id>[holds]`) maps to the last write concerning the entity before the bracket. A problem about a subject that no write in the payload names (a stored relationship that outlives an ended entity, a cascaded target, an end touched only through a relationship) maps to the last write concerning a subject that its message, found or expected value names, or else to the payload's root. Writes about a subject (create, relate, patch, replace and end-date of it) win over writes that only name it as a relationship end.

### D4. JSON forms

- Field names follow the Java records; `Optional.empty()` and empty collections are left out; temporary IDs are written with their `$`, as in payloads; IDs whose kind the field does not fix (in a receipt's `temporaryIds`, `created` and `ended`) are written as `{ "entity": "<id>" }` or `{ "relationship": "<id>" }`; `Value`s are written as JSON scalars by type, decimals from `BigDecimal.toPlainString()`.
- Problems: `{ code, path, message, expected, found, hint, ruleCode, location: { pointer, line, column } }`.

### D5. Generated JSON Schemas

- Values schema: `type: object`, `additionalProperties: false`, a property per effective attribute under its name (aliases are accepted by the reader, not by the schema), dates and instants with a `pattern` as well as a `format`, `deprecated: true` on deprecated attributes, and the range of a date or instant attribute stated in its description, because JSON Schema cannot order dates; `string` with `pattern` (anchored as `^(?:...)$`), `enum`, `minLength`, `maxLength`; `integer` with 32-bit or 64-bit bounds and the range; `number` for decimals with the range (`exclusiveMinimum` and so on); `boolean`; `string` with `format: date` or `format: date-time`. Descriptions and examples are copied.
- Transactions schema: the payload structure with `$defs` per concrete entity type and relationship type; `create` and `relate` are `oneOf` per type with an `enum` of the type's name and aliases, as the schema writes them, `$ref` to its values, and `values` required where the type has required attributes; the engine also accepts other capitalizations, which the generated schema does not; `patch` and `replace` values are checked by name only against the union of attribute names, since their type is known only from the store.

## Risks / Trade-offs

- [Patch values cannot be typed by the JSON Schema alone] → The engine validates them at commit; the schema still catches unknown attribute names.
- [Patterns in generated JSON Schemas] → Patterns are I-Regexp (ADR-0020). `cairn-documents` translates them to ECMAScript itself, because it does not depend on `cairn-core`; a test checks that the translation equals the engine's on sample patterns, and that a JSON Schema validator and the engine agree on sample values.
- [A create whose required value a later write of the same transaction sets is committed but invalid against the generated schema] → The spec limits its guarantee to payloads whose creates and relates give their required values themselves.
