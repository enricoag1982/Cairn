# Tasks

## 1. Engine

- [x] 1.1 End the other relationships of cascaded targets, in the receipt order of the spec (D1, D2)

## 2. Tests and example

- [x] 2.1 One test per scenario of the delta spec, named `Scenario: <name>`; existing receipt-order tests updated where the order is now longer
- [x] 2.2 Edge cases: incoming and outgoing relationships, symmetric types, relationships already ended by a write, relationships starting at the instant, deep cascades, durable reopen
- [x] 2.3 HR-11 without ending the decision by ID (D1); verify `./gradlew build` passes

## 3. Documentation

- [x] 3.1 Update the HR domain page (HR-11, gaps) and any guide or glossary entry that describes cascades, and bump `last-reviewed`
- [x] 3.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
