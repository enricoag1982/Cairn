# Tasks

## 1. Expression language

- [x] 1.1 `cairn.calendarDays` and `cairn.workingDays` in `cairn-cel`, declared with their overloads and computed in UTC (D1, D2)

## 2. Examples

- [x] 2.1 The test-only derived attributes `workingDaysTo2026` and `calendarDaysTo2026` in the test fixtures (D4)
- [x] 2.2 HR: `LeaveRequest.days` derived with `cairn.workingDays`, and its writers no longer give it (D4)

## 3. Tests

- [x] 3.1 One test per scenario of the delta spec, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [x] 3.2 Verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Update the write-checks and derive-attributes guides, the glossary, the reference domain page, the HR domain page (type table, gaps) and the architecture page; bump `last-reviewed`
- [x] 4.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
