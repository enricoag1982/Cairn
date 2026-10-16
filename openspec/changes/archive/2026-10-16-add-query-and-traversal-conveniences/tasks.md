# Tasks

## 1. API and engine

- [x] 1.1 `View.traverse` without a condition and without a maximum depth (D1)
- [x] 1.2 Traversal steps without a maximum depth, and `Traversal` with `Query.Builder.traverse(Traversal)` (D2)
- [x] 1.3 Queries from given entities, and the reads they record in rules (D3)
- [x] 1.4 Typed row accessors (D4)

## 2. Documents, codegen and the HR example

- [x] 2.1 The JSON form of queries and its JSON Schema: optional `maxDepth`, `ids` (D2, D3)
- [x] 2.2 `INVERSE` constants (D5)
- [x] 2.3 The HR example uses the conveniences (D6)

## 3. Tests

- [x] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [x] 3.2 Verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Update the query guide, the codegen guide, the HR domain page (gaps), the reference domain page (the test-only rule), the architecture page, and point ADR-0023 to this change's extension of the start; bump `last-reviewed`
- [x] 4.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
