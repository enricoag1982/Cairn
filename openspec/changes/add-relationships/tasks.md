# Tasks

## 1. API

- [x] 1.1 Add `RelationshipId`, the `Relate` write and `relate` on the transaction builder (D1)
- [x] 1.2 Add `RelationshipState`, `Link`, `Step`, and the relationship reads, navigation and traversal on `View` (D1)
- [x] 1.3 Add `RelationshipRule`, and the new problem codes with explanations (D1, D3)

## 2. Engine

- [x] 2.1 Store relationships as timelines per basis, and apply relate, patch, replace and end-date to them (D2)
- [x] 2.2 Add composition cascades (D4)
- [x] 2.3 Check ends, lifetimes, multiplicities and duplicates (D3)
- [x] 2.4 Navigation, symmetric types and traversal in views (D2)
- [x] 2.5 Run rules on relationships, generalizing the rule runner, its fit check, the matching of problems to subjects, and the rule problem explanations (D5)

## 3. Reference domain and tests

- [x] 3.1 Add RD-13 to the reference domain page, the relationship stories to `ReferenceStories`, and `TotalOwnershipRule` to the test fixtures (D5, D6)
- [x] 3.1a Update the existing tests that create accounts without a holder (D6)
- [x] 3.2 One test per scenario of `relationships` and of the new `entity-rules` requirement, named `Scenario: <name>`; verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Add `Link` and `Traversal` to `docs/glossary.md`, and update `Relationship`
- [x] 4.2 Update `docs/architecture/README.md`, `AGENTS.md` and `README.md` status, and bump `last-reviewed`
- [x] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings

## 5. Drift-review fixes

- [x] 5.1 A test named after the scenario "Every owner of Beta over time"; RD-13 with ACME's 60% (110%) in its fixture, Javadoc and scenario test
- [x] 5.2 Problem explanations: `END_NOT_EFFECTIVE` (the found value is the relationship's lifetime), `UNKNOWN_ENTITY` (also unknown relationship ends), `RELATIONSHIP_OUTLIVES_END` (ending them at or before the instant also works); `ReferenceStories` and `Receipt` Javadoc; verify `./gradlew build` passes

