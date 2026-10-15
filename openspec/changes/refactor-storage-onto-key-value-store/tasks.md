# Tasks

## 1. Engine

- [x] 1.1 The key-value interface and its in-memory implementation (D1)
- [x] 1.2 Key and value encodings (D2)
- [x] 1.3 Writing commits as batches (D3), and the `Ledger` that reads them (D4)
- [x] 1.4 Views, preparation, cascades, rechecks, purges, snapshots and recovery on the `Ledger` (D4, D5, D6); remove the maps of timelines

## 2. Tests

- [x] 2.1 Every existing test passes unchanged, apart from tests of replaced internals
- [x] 2.2 Tests of the keys a commit writes, of reading every basis of the reference stories as before, of indexes, and of purges leaving no key of what they removed
- [x] 2.3 Verify `./gradlew build` passes, and compare the time of the test suite before and after

## 3. Documentation

- [x] 3.1 Update the architecture page (building blocks, runtime view, risks) and the glossary if a term is needed, and bump `last-reviewed`
- [ ] 3.2 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
