# Tasks

## 1. Build and API

- [x] 1.1 Add the subprojects `cairn-codegen` and `cairn-gradle-plugin`, and the convention plugins `cairn.gradle-plugin` and `cairn.reference-types` (D1)
- [x] 1.2 Add `TypedEntity`, `TypedRelationship` and `TypedValues` to `cairn-api` (D2)
- [x] 1.3 Move the closest common supertype of the types that name a rule into `cairn-documents`, and use it in `cairn-agent-tools` (D5)

## 2. Generator

- [x] 2.1 Generate interfaces, snapshot records and `from` for traits, entity types and relationship types (D3)
- [x] 2.2 Generate the builders (D3)
- [x] 2.3 Generate the rule base classes (D3)
- [x] 2.4 Map names to Java (D4)
- [x] 2.5 Check the documents together, report located problems, and the command line (D5)

## 3. Gradle plugin

- [x] 3.1 The extension, the configuration and the task, forking the generator on Java 25 (D6)

## 4. Tests

- [x] 4.1 One test per scenario of `java-types`, named `Scenario: <name>` (D7)
- [x] 4.2 Compile generated code with all lint warnings as errors, for the reference model and for awkward names; verify `./gradlew build` passes

## 5. Documentation

- [x] 5.1 Add `Snapshot record` and `Rule base class` to `docs/glossary.md`, and update `Generated type`
- [x] 5.2 Write a guide in `docs/guides/` for generating types and writing rules against them
- [x] 5.3 Update `docs/architecture/README.md`, `AGENTS.md`, `README.md` and `openspec/config.yaml` (ADR-0021), and bump `last-reviewed`
- [ ] 5.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
