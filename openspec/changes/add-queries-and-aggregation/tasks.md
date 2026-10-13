# Tasks

## 1. API

- [ ] 1.1 The query records, builder, results and `View.query` in `cairn-api`, and the new problem codes (D1, D2)
- [ ] 1.2 Typed attribute references (D6)

## 2. Engine

- [ ] 2.1 Checking queries against a view's schema, with paths and hints (D2)
- [ ] 2.2 Evaluating starts, filters, follows and traversals (D2, D3)
- [ ] 2.3 Grouping, aggregates, columns, order and limit (D2)

## 3. JSON, tools and generated types

- [ ] 3.1 Reading queries as JSON or YAML, locating problems, the published JSON Schema, and results as JSON (D4)
- [ ] 3.2 The tool `cairn_query`, and the kind `query` of `cairn_json_schema` (D5)
- [ ] 3.3 Attribute constants on generated types, and reading rows as generated types (D6)

## 4. Tests

- [ ] 4.1 One test per scenario of `queries`, `agent-tools` and `java-types` in this change, named `Scenario: <name>`
- [ ] 4.2 The same reference queries through the builder, generated types and JSON give the same rows; verify `./gradlew build` passes

## 5. Documentation

- [x] 5.1 Add `Query`, `Binding` and `Query step` to `docs/glossary.md`
- [ ] 5.2 Write a guide in `docs/guides/` for writing queries, and update the agent guide
- [ ] 5.3 Update `docs/architecture/README.md`, `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0023), and bump `last-reviewed`
- [ ] 5.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
