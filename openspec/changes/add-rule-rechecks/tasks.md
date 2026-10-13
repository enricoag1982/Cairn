# Tasks

## 1. Engine

- [ ] 1.1 Recording reads in `ProvisionalView`, with the keys of D1, for the views of a rule context
- [ ] 1.2 Changes of a transaction from its working timelines (D2)
- [ ] 1.3 The index of checks, staged per transaction and applied at commit; trimming by writes and ends; purges (D3)
- [ ] 1.4 Re-checks after `RuleStage`: the gate, the rules left out, the order, the path and hint of problems (D4)
- [ ] 1.5 Rebuilding the index after recovery (D5)
- [ ] 1.6 Javadoc of `Rule`, `EntityRule`, `RelationshipRule` and `RuleContext`

## 2. Tests

- [ ] 2.1 The test-only rules `employer-holds-account` and `controlling-owner-has-lei` in the reference fixtures
- [ ] 2.2 One test per scenario of the delta specs, named `Scenario: <name>`
- [ ] 2.3 Unit tests for each key and each kind of change, reads replaced by a later check, trimming, dry runs, schema registrations and purges
- [ ] 2.4 HR-5 asserts that ending Cleo's `ManagedBy` is rejected (D6); verify `./gradlew build` passes

## 3. Documentation

- [ ] 3.1 Add `Re-check` and `Rule read` to `docs/glossary.md`
- [ ] 3.2 Update the reference domain (test-only rules), the HR domain page (HR-5, gaps), the architecture page and the guide for durable stores
- [ ] 3.3 Update `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0025), and bump `last-reviewed`
- [ ] 3.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
