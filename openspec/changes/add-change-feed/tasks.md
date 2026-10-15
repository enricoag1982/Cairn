# Tasks

## 1. API and engine

- [ ] 1.1 `Change`, `TransactionChange`, `SchemaChange`, `PurgeChange`, `Changed` and the `Store` methods in `cairn-api` (D1)
- [ ] 1.2 The `CHANGE` keys written by `Ledger.add`, with the changed periods (D2)
- [ ] 1.3 Reading changes from the index and the records, in every kind of store (D3)
- [ ] 1.4 Purges delete the keys of what they purge (D4)
- [ ] 1.5 The publisher and its subscriptions, completed when a store closes (D5)
- [ ] 1.6 Recovery writes the keys again; the RocksDB format version becomes 2 (D6)

## 2. Documents, tools and the HR example

- [ ] 2.1 Changes as JSON in `JsonResults` (D7)
- [ ] 2.2 The tool `cairn_list_changes` (D7)
- [ ] 2.3 The HR test that tells an approver of a new request (D8)

## 3. Tests

- [ ] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the `change-feed` scenarios in the RocksDB conformance run
- [ ] 3.2 Equivalence: the changes of random operations match what views at each basis and the basis before read, in every kind of store, before and after purges and reopening
- [ ] 3.3 Verify `./gradlew build` passes

## 4. Documentation

- [ ] 4.1 A guide to the change feed, and the glossary (Change, Change feed)
- [ ] 4.2 Update the architecture page, the HR domain page, AGENTS.md, README.md and `openspec/config.yaml` (ADR-0029), and bump `last-reviewed`; point ADR-0011 and ADR-0022 to ADR-0029
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
