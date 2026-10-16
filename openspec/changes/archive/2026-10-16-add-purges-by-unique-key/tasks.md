# Tasks

## 1. API and engine

- [x] 1.1 `PurgeKey`, `Purge.keys` and the builder's `entity(UniqueKey)` and `entity(UniqueKey, Instant)`, `PurgeResult.ResolvedKey` and `Purged.keys` (D1)
- [x] 1.2 Resolving keys in `PurgeCheck`, with problems at `@purge.key.<n>` and the new code `UNIQUE_KEY_AMBIGUOUS` (D2, D4)

## 2. Examples

- [x] 2.1 HR-12 erases Gus by his employee number (D5)

## 3. Tests

- [x] 3.1 One test per scenario of the delta spec, named `Scenario: <name>`, with the store scenarios in the RocksDB conformance run
- [x] 3.2 Verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Update the glossary (Purge), the purge sections of the durable store and RocksDB guides, the HR domain page (HR-12, gaps) and the architecture page; bump `last-reviewed`
- [x] 4.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
