# Tasks

## 1. Payloads

- [ ] 1.1 Read transaction payloads into transactions with typed values, reusing the strict node-tree reader of schema documents (D1, D2)
- [ ] 1.2 Locate reading, dry-run and commit problems in payloads (D3)
- [ ] 1.3 Write receipts, states, links, steps and problems as JSON (D4)

## 2. JSON Schemas

- [ ] 2.1 Generate JSON Schemas for the values of entity types and relationship types (D5)
- [ ] 2.2 Generate the JSON Schema for transaction payloads (D5)

## 3. Tests

- [ ] 3.1 One test per scenario of `json-payloads`, named `Scenario: <name>`
- [ ] 3.2 Check the reference stories' payloads against the generated JSON Schema; verify `./gradlew build` passes

## 4. Documentation

- [ ] 4.1 Add `Payload` to `docs/glossary.md`
- [ ] 4.2 Update `docs/architecture/README.md` and bump `last-reviewed`
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
