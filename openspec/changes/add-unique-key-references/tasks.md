# Tasks

## 1. API and engine

- [ ] 1.1 `UniqueKey` as a `Ref`, `View.entity(UniqueKey)`, the problem codes `INVALID_UNIQUE_KEY` and `UNIQUE_KEY_NOT_FOUND` (D1)
- [ ] 1.2 Resolving keys in transactions (D2)
- [ ] 1.3 Finding by key in views, recorded as rule reads (D3)
- [ ] 1.4 Keys in the log codec (D4)

## 2. Documents, tools and generated types

- [ ] 2.1 Keys in payloads, their read problems, and locating problems about key-named entities (D5)
- [ ] 2.2 `key` in `cairn_read_entity`, `cairn_navigate` and `cairn_traverse` (D6)
- [ ] 2.3 `by<Attribute>` methods in generated types (D7)

## 3. Tests and example

- [ ] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`
- [ ] 3.2 Tests of resolution edge cases: subtypes, aliases, a value at the end of a period, ends of a relate, dry runs, idempotency retries, durable reopen
- [ ] 3.3 HR-13 with keys (D8); verify `./gradlew build` passes

## 4. Documentation

- [ ] 4.1 Add `Unique key` to `docs/glossary.md`
- [ ] 4.2 Update the guides (agents, generated types, queries), the architecture page, the HR domain page, and the reference domain if it lists codes
- [ ] 4.3 Update `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0026), and bump `last-reviewed`
- [ ] 4.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
