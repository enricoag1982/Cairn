# Tasks

## 1. API

- [ ] 1.1 Add `Rule`, `EntityRule` and `RuleContext` (D1)
- [ ] 1.2 Add `Problem.ruleCode`, and the codes `RULE_VIOLATION` and `RULE_FAILED` with explanations (D4)

## 2. Engine

- [ ] 2.1 Accept rules in `StoreSettings`, with `ServiceLoader` discovery, and give their descriptors to the store's schema registry (D2)
- [ ] 2.2 Build the provisional view and run effective rules for changed segments after the declarative checks, in commits and dry runs (D3)
- [ ] 2.3 Turn rule reports, exceptions and undeclared codes into problems (D4)

## 3. Reference domain and tests

- [ ] 3.1 Add `LeiChecksumRule` and `NameNotBlankRule` to the test fixtures, and story RD-12 to the reference domain page (D5)
- [ ] 3.2 One test per scenario of `entity-rules`, named `Scenario: <name>`; verify `./gradlew build` passes

## 4. Documentation

- [ ] 4.1 Add `Rule context` to `docs/glossary.md` and update `Rule`
- [ ] 4.2 Update `docs/architecture/README.md` and bump `last-reviewed`
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
