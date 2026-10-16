# Tasks

## 1. Schema

- [x] 1.1 `classification` on attributes: definitions, builder, resolved attribute, validation with `INVALID_CLASSIFICATION`, and the attributes a derivation reads (D1)
- [x] 1.2 Schema documents, their writer and JSON Schema, the canonical form, stored definitions, and compatibility (D1)

## 2. Reads and writes

- [x] 2.1 `Clearance`, `View.restrictedTo` and `View.clearance`, withheld values in every returned state, and `withheld` on states (D2)
- [x] 2.2 Queries and keys over withheld attributes, with `ACCESS_DENIED` (D3)
- [x] 2.3 `Transaction.clearance` and writes of withheld attributes, in commits and dry runs (D4)

## 3. Agents and generated types

- [x] 3.1 The server's `--clearance`, tools through restricted views and commits, `withheld` in JSON results, and `cairn_describe` (D5)
- [x] 3.2 Generated types refuse withheld attributes (D6)

## 4. Examples

- [x] 4.1 HR: pay classified, HR-13's agent with `pay`, and HR-18 (D7)

## 5. Tests

- [x] 5.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [x] 5.2 Verify `./gradlew build` passes

## 6. Documentation

- [x] 6.1 Update the glossary, the agent server, query and codegen guides, the HR domain page (types, HR-13, HR-18, gaps), the reference domain page and the architecture page; bump `last-reviewed`
- [ ] 6.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
