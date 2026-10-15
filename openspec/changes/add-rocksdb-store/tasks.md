# Tasks

## 1. Engine

- [x] 1.1 `PersistentKeyValueStore`, `KeyValueStores` and the qualified exports (D1)
- [x] 1.2 Metadata keys written with each commit, registration and purge, and loaded when a store opens (D2, D3)
- [x] 1.3 Purges that flush and compact (D4)

## 2. `cairn-rocksdb`

- [x] 2.1 The subproject, its module, the version catalog entry and build logic (D5)
- [x] 2.2 The RocksDB backend and `RocksDbStores.open` (D5)

## 3. Tests

- [x] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`
- [x] 3.2 The scenario tests of the listed capabilities run against both backends in `./gradlew build` (D6)
- [x] 3.3 Equivalence with an in-memory store at every basis, a crash in a forked process, a failed write, a purge searched in every file, the format version
- [x] 3.4 Verify `./gradlew build` passes, and report the build time before and after

## 4. Documentation

- [ ] 4.1 A guide for RocksDB stores, and the glossary (RocksDB store)
- [ ] 4.2 Update the architecture page, `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0028), and bump `last-reviewed`
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
