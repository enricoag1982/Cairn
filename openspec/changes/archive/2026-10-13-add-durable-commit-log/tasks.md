# Tasks

## 1. API

- [x] 1.1 Add `DurableStore` to `cairn-api`, and `Stores.durable` to `cairn-core` (D1)

## 2. Commit log and recovery

- [x] 2.1 The record codec, with format version 1 (D3)
- [x] 2.2 Log segments, the directory lock, and writing each commit and schema registration before it is published (D2, D5)
- [x] 2.3 Recovery: snapshot, log, truncating an incomplete last record, refusing damage and unknown versions (D4)
- [x] 2.4 Snapshot files, and deleting what the two newest make unnecessary (D6)
- [x] 2.5 Skip generated IDs that the store already has (D7)

## 3. Tests

- [x] 3.1 One test per scenario of `durability`, named `Scenario: <name>` (D8)
- [x] 3.2 The equivalence helper, and a test that commits every reference story to a durable store and an in-memory store and compares them, before and after reopening; verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Add `Durable store`, `Recovery` and `Snapshot file` to `docs/glossary.md`, and update `Commit log`
- [x] 4.2 Write a guide in `docs/guides/` for running a durable store
- [x] 4.3 Update `docs/architecture/README.md`, `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0022), and bump `last-reviewed`
- [x] 4.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
