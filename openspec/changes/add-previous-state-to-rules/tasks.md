# Tasks

## 1. API, engine and codegen

- [ ] 1.1 `RuleContext.before()` and `beforeAt(Instant)` (D1)
- [ ] 1.2 Views of the committed state in the rule stage, with recorded reads, in commits, dry runs, re-checks and learning after recovery (D2)
- [ ] 1.3 `previous(version, context)` in generated rule base classes (D3)

## 2. Examples

- [ ] 2.1 The test-only rule `lei-kept` (D4)
- [ ] 2.2 The HR rule `decided-requests-stay` and the story HR-16 (D4)

## 3. Tests

- [ ] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [ ] 3.2 Verify `./gradlew build` passes

## 4. Documentation

- [ ] 4.1 Update the codegen guide's rule section, the HR domain page (rule table, HR-16, gaps), the reference domain page, the architecture page and ADR-0018's More Information; bump `last-reviewed`
- [ ] 4.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
