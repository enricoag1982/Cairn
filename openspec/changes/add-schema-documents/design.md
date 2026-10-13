# Design

## Context

ADR-0012 makes YAML 1.2 schema documents the canonical definition of types, with a published JSON Schema, and ADR-0017 makes agents first-class users. This change builds the format on the definitions of `add-type-model` and `add-self-describing-schema`, which must be archived first. The requirements are in `specs/schema-documents/spec.md` and `specs/schema-evolution/spec.md`.

## Goals / Non-Goals

**Goals:**

- One format, YAML 1.2, read strictly and written deterministically, with JSON as a writing style of the same format.
- Every problem a person or an agent sees for a document points to a place in it.
- The engine stays free of parsing libraries: only the new `cairn-documents` module depends on SnakeYAML Engine.

**Non-Goals:**

- Preserving comments or formatting when a document is rewritten.
- Command-line tools, Gradle tasks and MCP tools that use the reader: later changes.

## Decisions

### D1. A new subproject, `cairn-documents`

- Module `io.github.enricoag1982.cairn.documents`, exporting package `io.github.enricoag1982.cairn.documents`. It depends on `cairn-api` and on SnakeYAML Engine 3.1.1, not on `cairn-core`.
- Its tests depend on `cairn-core` (to register documents), on the `cairn-api` test fixtures, and on json-schema-validator 3.0.7 (networknt) to check the published JSON Schema.
- Alternative: putting the reader in `cairn-core`. Rejected, because applications that only use the Java builders would then carry a parser, and the reader needs nothing from the engine.

### D2. SnakeYAML Engine, through its node tree

- SnakeYAML Engine implements YAML 1.2, has no dependencies, and reads JSON as YAML. Release 3.2 is ten days old; 3.1.1 (August 2026) is used until 3.2 has been out for some weeks.
- The reader composes the node tree (not Java objects), so that every node keeps its start mark (line and column) and every scalar keeps its text and whether it was quoted. Literals are converted from the scalar text, so a decimal never passes through a `double`.
- Load settings forbid duplicate keys. Anchors, aliases and explicit tags are found on the node tree and reported as problems. More than one document in a stream is a problem.
- Alternative: Jackson with its YAML module. Rejected for the engine path: it reads YAML 1.1 through SnakeYAML, and it is a much larger dependency.

### D3. Public API

- `SchemaDocuments.read(String text)` returns a sealed `DocumentReadResult`: `Read(SchemaDocument document)` or `Invalid(List<Problem> problems)`.
- `SchemaDocument(Origin origin, SchemaDefinition definition, DocumentLocations locations)`. `document.locate(List<Problem>)` returns the problems with their locations, for problems from `validate`, `plan` or `register` of that definition.
- `SchemaDocuments.writeYaml(Origin, SchemaDefinition)` and `SchemaDocuments.writeJson(Origin, SchemaDefinition)` return text.
- `SchemaDocuments.jsonSchema()` returns the published JSON Schema text. It is a resource in the module, `io/github/enricoag1982/cairn/documents/schema-document-1.schema.json`, with `$id` `urn:cairn:schema-document:1`.

### D4. Locations on problems

- `cairn-api` gains `DocumentLocation(String pointer, int line, int column)`, with lines and columns counted from 1, and `Problem` gains `Optional<DocumentLocation> location` with a method `Problem.at(DocumentLocation)` that returns a located copy. The engine never sets locations; it does not know about documents.
- The existing shorter constructors of `Problem` remain.
- `DocumentLocations` maps element paths (as in the `type-model` spec) to locations: a type to its key under `traits`, `entityTypes` or `relationshipTypes`; an attribute to its key under `attributes`; an inverse name to the `inverseName` key; a rule to its entry in `rules`; `@origin` to the `origin` key. A path that matches no element is located at the document's root.
- Paths match case-insensitively, as names do.

### D5. Problems of the document itself

- New problem codes: `DOCUMENT_SYNTAX` (YAML syntax error), `UNSUPPORTED_FORMAT_VERSION`, `UNSUPPORTED_YAML_FEATURE` (anchor, alias, explicit tag, several documents), `DUPLICATE_KEY`, `UNKNOWN_KEY`, `MISSING_KEY` and `INVALID_VALUE` (a value of the wrong kind, a malformed multiplicity or range).
- Their path is the element path when the problem is inside a type, attribute, inverse name or rule, and `@document` otherwise. Their location is always set.
- `UNKNOWN_KEY` gets the closest known key at that position as its hint, with the same distance rule as names (at most two letters).
- The reader reports every problem it finds in one pass, except a syntax error, which stops it.

### D6. Literals in documents

- A scalar is converted by the attribute's value type: an int, long or decimal from a plain (unquoted) number, a boolean from plain `true` or `false`, a string, date or instant from any string scalar.
- When the scalar does not fit the value type, the reader keeps it as a literal of its own YAML type (string, boolean, integer as int or long, or decimal), so that validation reports `INVALID_EXAMPLE` or `RANGE_LITERAL_MISMATCH` with the expected and found types. Validation stays in one place, the engine.
- A range is a mapping with at most one of `atLeast` and `greaterThan`, and at most one of `atMost` and `lessThan`; both forms of the same side are an `INVALID_VALUE`.

### D7. Writing

- The writer emits `cairn`, `origin`, `traits`, `entityTypes` and `relationshipTypes` in that order, types and attributes in definition order, and properties in a fixed order per element. Defaults are left out, so a reader filling in defaults gives back equal records.
- YAML is written in block style, quoting strings only where YAML 1.2 would read them as another type. JSON is written with two-space indentation.
- Round trip is tested on the reference model and on definitions generated by a small property-style generator in the tests.

### D8. The published JSON Schema

- Hand-written, draft 2020-12, with `additionalProperties: false` everywhere, descriptions on every property, and `enum`s for value types, kinds and multiplicities.
- A test reads the keys the reader accepts and the keys the JSON Schema declares, and fails if they differ, so the two cannot drift.
- Patterns of `pattern` attributes are not checked by the JSON Schema; they are data.

### D9. Stored definitions

- `SchemaRegistry.storedDefinition(String originName)` returns `Optional<StoredDefinition>`, with `StoredDefinition(Origin origin, SchemaDefinition definition)`. The in-memory registry already keeps each origin's latest applied definition for compatibility checks; this exposes it.

### D10. The reference model as a document

- `docs/reference/reference-model.cairn.yaml` holds the reference domain model, with descriptions. The page stays canonical: a test reads the document and compares it with the `ReferenceModel` fixture, which `ReferenceModelTest` already compares with the page. The page links the document.
- The convention plugin `cairn.reference-domain-tests` adds the document as a test input, so tests rerun when it changes.

## Risks / Trade-offs

- [YAML's implicit typing (`no`, `1.10`) surprises authors] → YAML 1.2 core schema has fewer surprises than 1.1; the JSON Schema and the reader reject values of the wrong kind with the expected type.
- [Locations of engine problems depend on paths matching elements] → Paths are already specified; a path that matches nothing still gets the root location rather than none.
- [The JSON Schema and the reader can disagree] → A test compares their key sets, and the scenarios check both on the same documents.
- [Test-only JSON Schema validator pulls Jackson into the test classpath] → It stays out of the runtime classpath.
