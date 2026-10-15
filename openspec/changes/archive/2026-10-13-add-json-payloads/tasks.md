# Tasks

## 1. Payloads

- [x] 1.1 Read transaction payloads into transactions with typed values, reusing the strict node-tree reader of schema documents (D1, D2)
- [x] 1.2 Locate reading, dry-run and commit problems in payloads (D3)
- [x] 1.3 Write receipts, states, links, steps and problems as JSON (D4)

## 2. JSON Schemas

- [x] 2.1 Generate JSON Schemas for the values of entity types and relationship types (D5)
- [x] 2.2 Generate the JSON Schema for transaction payloads (D5)

## 3. Tests

- [x] 3.1 One test per scenario of `json-payloads`, named `Scenario: <name>`
- [x] 3.2 Check the reference stories' payloads against the generated JSON Schema; verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Add `Payload` to `docs/glossary.md`
- [x] 4.2 Update `docs/architecture/README.md` and bump `last-reviewed`
- [x] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings

## 5. Drift-review fixes

- [x] 5.1 Always write a state's `values`; locate `@transaction.<key>` at that top-level key; write problem JSON keys in record order
- [x] 5.2 Generated schemas: patterns for dates and instants, and `values` required in `create` and `relate` branches of types with required attributes
- [x] 5.3 Javadoc and explanations: instants accept offsets and are converted to UTC (`Payloads`, the `instant` definition of the transactions schema, `INVALID_VALUE`); `Problem.path` with `@write.<n>.<attribute>`; the stale `IRegexp` comment about exporting
- [x] 5.4 Tests for the new scenarios (patching a stored relationship by its ID, a replace without values, a receipt as JSON); verify `./gradlew build` passes
- [x] 5.5 Widen the date pattern of generated schemas to every form the reader accepts, and say in the published schema-document JSON Schema that instants with an offset are converted to UTC
