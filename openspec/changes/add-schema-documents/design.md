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
- The reader finds duplicate keys, anchors, aliases, explicit tags and merge keys on the node tree and reports them as problems. An explicit tag equal to the implicit one (`!!str`) is invisible on the node tree, so the reader extends SnakeYAML Engine's `Composer` to remember tagged nodes. More than one document in a stream is a problem.
- SnakeYAML Engine's module descriptor does not export `api.lowlevel`, so the reader builds the tree with `Composer`, `ParserImpl` and `StreamReader` directly.
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

- New problem codes: `DOCUMENT_SYNTAX` (YAML syntax error), `UNSUPPORTED_FORMAT_VERSION`, `UNSUPPORTED_YAML_FEATURE` (anchor, alias, explicit tag, merge key, several documents), `DUPLICATE_KEY`, `UNKNOWN_KEY`, `MISSING_KEY` and `INVALID_VALUE` (a value of the wrong kind, a malformed multiplicity or range).
- Their path is the path of the nearest element the reader can name (a type or an attribute, and a rule when its name is readable), `@origin` inside the `origin` mapping, and `@document` outside every element. Their location is always set.
- `UNKNOWN_KEY` gets the closest known key at that position as its hint, with the same distance rule as names (at most two letters).
- The reader reports every problem it finds in one pass, sorted by line and column, except a syntax error or an unsupported format version, which stop it: the keys of another format version are unknown.

### D6. Literals in documents

- Numbers follow the JSON number grammar; YAML-only forms (`0x10`, `0o17`, `+1`, `.5`, `1.`, `01`, `.inf`, `.nan`) are `INVALID_VALUE`, so that every document is also valid JSON in meaning. Booleans are read as the YAML 1.2 core schema reads them (`true`, `True`, `TRUE`, and the same for false); the writer writes `true` and `false`.
- A top-level `$schema` key is accepted and ignored, so that JSON editors can find the published JSON Schema.
- A scalar is converted by the attribute's value type: an int, long or decimal from a plain (unquoted) number, a boolean from plain `true` or `false`, a string, date or instant from any string scalar.
- When the scalar does not fit the value type, the reader keeps it as a literal of its own YAML type (string, boolean, integer as int or long, or decimal), so that validation reports `INVALID_EXAMPLE` or `RANGE_LITERAL_MISMATCH` with the expected and found types. Validation stays in one place, the engine.
- A range is a mapping with at most one of `atLeast` and `greaterThan`, and at most one of `atMost` and `lessThan`; both forms of the same side are an `INVALID_VALUE`.

### D7. Writing

- The writer emits `cairn`, `origin`, `traits`, `entityTypes` and `relationshipTypes` in that order, types and attributes in definition order, and properties in a fixed order per element. Defaults are left out, so a reader filling in defaults gives back equal records.
- YAML is written in block style. Strings are quoted where YAML 1.2 would read them as another type, where YAML 1.1 tools would (such as `yes` and `no`), and where they contain special characters; the origin version and multiplicities are always quoted. JSON is written with two-space indentation.
- A number written for an attribute of a wider value type (an int bound of a decimal attribute) is read back in the attribute's type, so round trips are exact only up to that widening, as the spec says.
- Round trip is tested on the reference model and on definitions generated by a small property-style generator in the tests.

### D8. The published JSON Schema

- Hand-written, draft 2020-12, with `additionalProperties: false` everywhere, descriptions on every property, and `enum`s for value types, kinds and multiplicities.
- A test reads the keys the reader accepts and the keys the JSON Schema declares, and fails if they differ, so the two cannot drift.
- Patterns of `pattern` attributes are not checked by the JSON Schema; they are data.
- JSON Schema counts a number with a zero fraction (`1.0`) as an integer, while the reader rejects it where an integer is expected; the spec states this one exception.
- The schema allows a top-level `$schema` key, so that JSON editors can find it. Hosting the JSON Schema at a URL comes with the first release; until then editors use the file in the module's resources.

### D9. Stored definitions

- `SchemaRegistry.storedDefinition(String originName)` returns `Optional<StoredDefinition>`, with `StoredDefinition(Origin origin, SchemaDefinition definition)`. The in-memory registry already keeps each origin's latest applied definition for compatibility checks; this exposes it.

### D10. The reference model as a document

- `docs/reference/reference-model.cairn.yaml` holds the reference domain model, with YAML comments for readers but no descriptions, so that it equals the `ReferenceModel` fixture. The page stays canonical: a test reads the document and compares it with the `ReferenceModel` fixture, which `ReferenceModelTest` already compares with the page. The page links the document.
- The convention plugin `cairn.reference-domain-tests` adds the document as a test input, so tests rerun when it changes.
- The `Owns.share` range bounds in the `ReferenceModel` fixture become decimal values, because a document reads bounds by the attribute's value type and decimal values compare by scale.

## Risks / Trade-offs

- [YAML's implicit typing (`no`, `1.10`) surprises authors] → YAML 1.2 core schema has fewer surprises than 1.1; the JSON Schema and the reader reject values of the wrong kind with the expected type.
- [Locations of engine problems depend on paths matching elements] → Paths are already specified; a path that matches nothing still gets the root location rather than none.
- [The JSON Schema and the reader can disagree] → A test compares their key sets, and the scenarios check both on the same documents.
- [Test-only JSON Schema validator pulls Jackson into the test classpath] → It stays out of the runtime classpath.
