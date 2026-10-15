# Tasks

## 1. API and engine

- [ ] 1.1 `Check`, the `checks` of type definitions and builders, the schema's declared and effective checks, and the problem codes with their explanations (D1)
- [ ] 1.2 The package `expressions` in `cairn-api`, and finding a language through `ServiceLoader` or the settings (D2)
- [ ] 1.3 Compiling checks at validation, planning and registration; `CHECKS_UNAVAILABLE` (D2)
- [ ] 1.4 Running checks at commit and in dry runs; stores opened without a language (D4)
- [ ] 1.5 Evolution rules for checks (D5)

## 2. Module, documents and examples

- [ ] 2.1 The subproject `cairn-cel` on `dev.cel:cel`, in the version catalog (D3)
- [ ] 2.2 `checks` in schema documents, writing, the JSON Schema and located problems (D6)
- [ ] 2.3 The agent server's runtime path includes `cairn-cel`
- [ ] 2.4 The HR example's check `leave-dates-in-order` replaces the Java rule (D7)

## 3. Tests

- [ ] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the commit scenarios in the RocksDB conformance run where they use stores
- [ ] 3.2 Unit tests of the CEL module: every value type, absent attributes, the cost limit, positions of compile errors
- [ ] 3.3 Verify `./gradlew build` passes, and report the size of the new dependencies

## 4. Documentation

- [ ] 4.1 A guide to writing checks, and the glossary (Check)
- [ ] 4.2 Update the architecture page, the HR domain page, the reference domain page, AGENTS.md (overview, repository map), README.md and `openspec/config.yaml` (ADR-0030), and bump `last-reviewed`; point ADR-0018 to ADR-0030
- [ ] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
