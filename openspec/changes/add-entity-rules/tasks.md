# Tasks

## 1. API

- [x] 1.1 Add `Rule`, `EntityRule` and `RuleContext` (D1)
- [x] 1.2 Add `Problem.ruleCode`, and the codes `RULE_VIOLATION`, `RULE_FAILED` and `RULE_UNAVAILABLE` with explanations (D2, D4)

## 2. Engine

- [x] 2.1 Accept rules in `StoreSettings`, with `ServiceLoader` discovery, and give their descriptors to the store's schema registry (D2)
- [x] 2.2 Build the provisional view and run effective rules for changed segments after the declarative checks, in commits and dry runs (D3)
- [x] 2.3 Turn rule reports, exceptions and undeclared codes into problems (D4)

## 3. Reference domain and tests

- [x] 3.1 Add `LeiChecksumRule` and `NameNotBlankRule` to the test fixtures, and story RD-12 to the reference domain page (D5)
- [x] 3.2 One test per scenario of `entity-rules`, named `Scenario: <name>`; verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Add `Rule context` to `docs/glossary.md` and update `Rule`
- [x] 4.2 Update `docs/architecture/README.md` and bump `last-reviewed`
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings

## 5. Drift-review fixes

- [x] 5.1 Rename `StoreSettings.availableRules` to `declaredRules`, and reject any transaction that writes an entity whose type names a declared rule (D2)
- [x] 5.2 Refuse writes while rules run; turn any `Throwable` except `VirtualMachineError` and unknown attributes into `RULE_FAILED`; update the `Rule`, `EntityRule` and `RuleContext` Javadoc (verdict per instant, transaction time as now, thread safety) (D3, D4)
- [x] 5.3 Tests for the new scenarios (a rule that tries to write, ending an entity whose rule has no code) and the updated ones; verify `./gradlew build` passes

