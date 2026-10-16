# Tasks

## 1. API, engine and module

- [x] 1.1 `derived` on `AttributeDefinition`, `AttributeBuilder` and `Attribute`; problem codes; `CHECKS_UNAVAILABLE` renamed `EXPRESSIONS_UNAVAILABLE` (D1)
- [x] 1.2 `compileDerivation` and `CompiledDerivation`; the exceptions renamed; derivations in `cairn-cel` with the result mapping (D2)
- [x] 1.3 Compiling derivations at validation, planning and registration (D3)
- [x] 1.4 Computing derived values at commit and in dry runs; rejecting writes that set them; stores opened without a language (D4)
- [x] 1.5 Evolution rules for derived attributes (D5)

## 2. Documents, payloads, codegen and examples

- [x] 2.1 `derived` in schema documents, the published JSON Schema and located problems (D6)
- [x] 2.2 Read-only derived attributes in generated values schemas, kept out of the payload schema; located `DERIVED_ATTRIBUTE_WRITTEN` (D6)
- [x] 2.3 Builders without setters for derived attributes; snapshot records read them (D7)
- [x] 2.4 The HR example's `fullName` (D9), and derived attributes kept in schema records (D8)

## 3. Tests

- [x] 3.1 One test per scenario of the delta specs, named `Scenario: <name>`, with the commit scenarios in the RocksDB conformance run
- [x] 3.2 Unit tests of derivations in `cairn-cel`: every result type, ranges, dates at midnight, `null`, errors
- [x] 3.3 Verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 A guide to derived attributes, or a section of the guide to writing checks, and the glossary (Derived attribute)
- [x] 4.2 Update the architecture page, the HR and reference domain pages, AGENTS.md, README.md and `openspec/config.yaml` (ADR-0031), and bump `last-reviewed`; point ADR-0018 and ADR-0030 to ADR-0031
- [x] 4.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
