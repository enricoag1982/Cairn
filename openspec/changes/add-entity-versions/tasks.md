# Tasks

## 1. Refactor

- [x] 1.1 Rename `Literal` to `Value` and its records to `StringValue`, `BooleanValue`, `IntValue`, `LongValue`, `DecimalValue`, `DateValue` and `InstantValue` in every module; verify `./gradlew build` passes, and commit it on its own (D1)

## 2. API (`cairn-api`, package `io.github.enricoag1982.cairn.data`)

- [ ] 2.1 Add `EntityId`, `TemporaryId`, `Ref`, `EffectivePeriod`, `ConflictCheck` and `IdGenerator` (D2)
- [ ] 2.2 Add `Transaction` with its builder, and the writes `Create`, `Patch`, `Replace` and `EndDate` (D2)
- [ ] 2.3 Add `CommitResult`, `DryRunResult`, `Receipt` and `TransactionRecord` (D2)
- [ ] 2.4 Add `Coordinates`, `KnownAt`, `View`, `EntityState` and `Store` (D2)
- [ ] 2.5 Add the new problem codes with explanations (D6)

## 3. Engine (`cairn-core`)

- [ ] 3.1 Add `IdGenerators` with UUIDv7 and deterministic generators (D8)
- [ ] 3.2 Add the commit sequence and build the store's schema registry on it (D3)
- [ ] 3.3 Keep timelines per entity and basis, and apply create, patch, replace and end-date (D4, D5)
- [ ] 3.4 Validate the resulting state: types, values, constraints, required and deprecated attributes, uniqueness, conflicts, references and IDs (D6)
- [ ] 3.5 Commit, dry run, idempotency and the transaction history (D7)
- [ ] 3.6 Views: coordinates, entity reads, listings with subtypes, timelines, names and value types of the view's schema (D4, D5)
- [ ] 3.7 `Stores` and `StoreSettings`

## 4. Tests

- [ ] 4.1 Move the settable test clock to the test fixtures and add `ReferenceStories` (D9)
- [ ] 4.2 One test per scenario of `transactions`, `entity-versions`, `views` and the new `schema-evolution` requirement, named `Scenario: <name>`
- [ ] 4.3 Unit tests for timeline splitting and merging, and for the ID generators; verify `./gradlew build` passes

## 5. Documentation

- [ ] 5.1 Add to `docs/glossary.md`: dry run, idempotency key, temporary ID, receipt, principal, store, timeline; update entity version and view
- [ ] 5.2 Update the building blocks and runtime view in `docs/architecture/README.md`, and the status in `AGENTS.md` and `README.md`; bump `last-reviewed`
- [ ] 5.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
