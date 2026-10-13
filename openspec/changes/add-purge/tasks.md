# Tasks

## 1. API

- [x] 1.1 `Purge`, `PurgeResult`, `PurgeRecord`, `Store.purge` and `Store.purges`, and the problem code `IDEMPOTENCY_KEY_PURGED` (D1, D3)

## 2. Engine

- [x] 2.1 Checking a purge: unknown IDs, composition targets, minimums at the other end (D2)
- [x] 2.2 Removing from the in-memory store, purged IDs, purged idempotency keys, purge records (D3)
- [x] 2.3 Durable stores: logging purges, rewriting files, recovery that completes a purge (D4)

## 3. Tests

- [x] 3.1 One test per scenario of `purge`, named `Scenario: <name>`
- [x] 3.2 Equivalence of a durable store and an in-memory twin through purges; verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Update `Purge` and add `Purge record` in `docs/glossary.md`
- [x] 4.2 Update the guide for durable stores, the reference domain and the architecture page
- [x] 4.3 Update `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0024), and bump `last-reviewed`
- [x] 4.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
