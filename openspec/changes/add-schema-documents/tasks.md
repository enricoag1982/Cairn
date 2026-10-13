# Tasks

## 1. Build

- [ ] 1.1 Add SnakeYAML Engine 3.1.1 and json-schema-validator 3.0.7 to `gradle/libs.versions.toml`; add the subproject `cairn-documents` with the `cairn.java-library` convention plugin and a `module-info.java` (D1)
- [ ] 1.2 Add `docs/reference/reference-model.cairn.yaml` as a test input in the `cairn.reference-domain-tests` convention plugin (D10)

## 2. API and engine

- [ ] 2.1 Add `DocumentLocation`, `Problem.location` and `Problem.at` to `cairn-api`, keeping the shorter constructors (D4)
- [ ] 2.2 Add the document problem codes with explanations (D5)
- [ ] 2.3 Add `SchemaRegistry.storedDefinition` and `StoredDefinition`, and implement it in `cairn-core` (D9)

## 3. Documents

- [ ] 3.1 Read documents with SnakeYAML Engine's node tree: format version, origin, traits, entity types, relationship types, every property, defaults, literals and ranges (D2, D6)
- [ ] 3.2 Report document problems with locations and hints, and reject anchors, aliases, explicit tags, duplicate keys and multi-document streams (D5)
- [ ] 3.3 Build `DocumentLocations` and `SchemaDocument.locate` (D4)
- [ ] 3.4 Write documents as YAML and as JSON (D7)
- [ ] 3.5 Write the JSON Schema resource and `SchemaDocuments.jsonSchema()` (D8)

## 4. Reference model and tests

- [x] 4.1 Write `docs/reference/reference-model.cairn.yaml` and link it from the reference domain page (D10)
- [ ] 4.2 One test per scenario of `schema-documents` and of the new `schema-evolution` requirement, named `Scenario: <name>`
- [ ] 4.3 Round-trip tests over generated definitions, and the test that compares the JSON Schema's keys with the reader's (D7, D8)
- [ ] 4.4 Verify `./gradlew build` passes

## 5. Documentation

- [x] 5.1 Add `Document location` to `docs/glossary.md`, and update `Schema document`
- [ ] 5.2 Add `cairn-documents` to the building blocks in `docs/architecture/README.md` and to the repository map in `AGENTS.md`; bump `last-reviewed`
- [ ] 5.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
