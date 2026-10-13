# Proposal

## Why

ADR-0012 makes schema documents the canonical definition of types: text that people review in pull requests and that agents write, check and send to a running store (ADR-0017).
So far a schema can only be built in Java. Agents need a published format with a JSON Schema to build documents from, problems that point to the line they must fix, and a way to read the stored schema back as a document, change it and plan the change.

## What Changes

- A schema document format, version 1, in YAML 1.2. Because JSON is valid YAML 1.2, the same documents can be sent as JSON. A document holds one origin and its traits, entity types and relationship types, keyed by name, with every property a schema definition has (including descriptions, examples, string constraints and rule references from `add-self-describing-schema`).
- Strict reading: unknown keys, values of the wrong kind, and YAML features outside a safe subset (anchors, aliases, explicit tags, duplicate keys, several documents in one stream) are problems, with the closest known key as a hint for misspellings.
- Locations: every problem about a document, and every problem that validating, planning or registering its definition reports, can be located in the document by a JSON Pointer, a line and a column.
- Writing an origin and a schema definition as a document, in YAML block style or as JSON. Reading a written document gives back the same origin and definition.
- A published JSON Schema for schema documents, which editors and agents use to complete and check documents.
- Reading a stored origin's definition back from the schema registry, so that a tool can export it as a document.
- The reference domain is also kept as a schema document, next to its page.

### Non-goals

- Running a schema document through a command-line tool or a Gradle plugin: `add-java-type-generation` and `add-agent-tools`.
- Formats other than YAML 1.2 (ADR-0012).
- A JSON Schema per entity type for entity payloads: `add-entity-versions`.
- Comments in written documents: writing produces data only.

## Capabilities

### New Capabilities

- `schema-documents`: the document format, reading with locations, writing, and the published JSON Schema

### Modified Capabilities

- `schema-evolution`: reading the stored definition of an origin

## Impact

- **Decisions:** implements ADR-0012 (schema documents in YAML 1.2 with a published JSON Schema) and serves ADR-0017. Both are accepted. It depends on `add-self-describing-schema` for the properties a document holds.
- **Code:** a new subproject, `cairn-documents`, with the reader, the writer and the JSON Schema. `cairn-api` gains document locations on problems and the stored-definition query on `SchemaRegistry`; `cairn-core` implements the query.
- **Dependencies:** SnakeYAML Engine 3.1.1 at runtime, in `cairn-documents` only; json-schema-validator 3.0.7 (networknt) for tests only. Approved by the user with the AI-native roadmap.
- **Docs:** the reference domain gains `reference-model.cairn.yaml`; the glossary (document location), the architecture page and `AGENTS.md` (repository map).
