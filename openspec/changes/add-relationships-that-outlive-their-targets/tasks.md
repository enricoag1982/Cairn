# Tasks

## 1. Schema

- [ ] 1.1 `outlivesTarget` in `RelationshipTypeDefinition`, the builder and `RelationshipType`, with validation (D1)
- [ ] 1.2 Schema documents, their writer and JSON Schema, the canonical form, stored definitions, agent tool descriptions and the Javadoc of generated relationship types (D1)
- [ ] 1.3 Compatibility: declaring is compatible, removing is breaking (D5)

## 2. Engine

- [ ] 2.1 Lifetimes and cascades for types that outlive their targets (D2)
- [ ] 2.2 Multiplicities at both ends (D3)
- [ ] 2.3 Navigation, query follow steps and traversals read an ended target's last version, with recorded reads (D4)

## 3. Examples

- [ ] 3.1 `DecidedBy` outlives its target; HR-17; the generated company ends managers' employments (D6)

## 4. Tests

- [ ] 4.1 One test per new scenario of the delta specs, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [ ] 4.2 Verify `./gradlew build` passes

## 5. Documentation

- [ ] 5.1 Update the glossary, the HR domain page (types, HR-17, gaps), the reference domain page (test-only types) and the architecture page; bump `last-reviewed`
- [ ] 5.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
