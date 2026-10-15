---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Define types in schema documents and generate Java types from them

## Context and Problem Statement

Users define entity types, traits and relationship types ([ADR-0010](0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md)).
We need a way to write those definitions that people and AI agents can both write, check and review, that registers in a running store, and that still gives Java developers typed access to data.
The first version of this ADR chose annotated Java interfaces. Then Cairn was required to be AI-native ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)): agents must be able to add types to a running store, which rules out definitions that need a Java compiler.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md) and the [AI-native research note](../research/2026-10-13-ai-native-schema-and-rules.md).

## Decision Drivers

- Agents and tools can write, read, check and compare definitions without a compiler
- Types registered in a running store are the normal case
- Editors complete and check definitions as they are typed
- Definitions are reviewed and versioned like an API, as text files in Git
- Typed Java access to data, without managed objects or sessions
- No language of our own to maintain: no parser, no editor plugin
- One format to specify, parse, document and test
- Custom logic stays in Java ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md))

## Considered Options

- Schema documents in YAML 1.2, with a published JSON Schema, generating Java types
- Schema documents in JSON only
- Annotated Java interfaces, processed at compile time
- An existing modeling language: LinkML, GraphQL SDL, Smithy or Pkl
- A schema language of our own
- Only a programmatic schema API (builders)

## Decision Outcome

Chosen option: "Schema documents in YAML 1.2, with a published JSON Schema, generating Java types", because it is the only option that agents can produce at runtime, that editors check without a plugin, and that still gives typed Java access.

- **The schema document is the canonical definition.** One document belongs to one origin and holds its traits, entity types and relationship types, with their attributes, constraints, descriptions, examples and rule references. A format version (`cairn: 1`) heads every document.
- **One format: YAML 1.2.** People write documents in YAML, in files named `*.cairn.yaml`. JSON is valid YAML 1.2, so APIs and agent tools send and receive the same documents written as JSON, with no second format to specify or parse. Documents use the glossary's names for concepts, such as supertype, traits, source, target and inverse name. Cairn reads a JSON-compatible subset of YAML 1.2, without custom tags or anchors, so that a document means the same whether it is written in block style or as JSON.
- **A published JSON Schema** describes the document format. Editors such as IntelliJ IDEA and VS Code use it for completion and checks; agents use it to build documents.
- **A document is read into an origin and a schema definition**, the input of the programmatic schema API (`Origin` and `SchemaDefinition`). `add-schema-documents` extends the definitions compatibly with what documents carry and definitions lack so far: descriptions, examples, allowed values, patterns, lengths and rule references. Registering a document goes through the schema registry, with its validation, compatibility checks and origins.
- **Problems keep the element path** of the `type-model` spec, such as `Account.active`, and add where the element is in the document: a JSON Pointer, a line and a column.
- **Types registered at runtime** by a tool, an agent or an administration screen are equal to types shipped with an application. Each type records its origin.
- **Java types are generated from documents** by a build step, offered as a Gradle plugin: immutable value types, builders, a typed query model, and base types for the rules the documents name. Generated types are values, not managed objects: a snapshot at a view's coordinates, with no lazy loading, change tracking or session. Every change goes through a transaction.
- **The engine never depends on generated classes.** It works on the meta-model, by type and attribute identifiers. Types without generated classes, such as types an agent registered at runtime, are read and written through the generic API, as JSON payloads or generic values.
- A typed query returns entities of unknown subtypes through their nearest known Java supertype, with extra attributes reachable through the generic API.
- The stored schema can be exported as schema documents, and as a JSON Schema per entity type for payloads ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)).
- Annotated Java interfaces are not planned. They stay possible as another way to write a schema document.

### Consequences

- Good, because agents and tools create, check and preview definitions without a build, so runtime types are first-class.
- Good, because the format is language-neutral, and consumers outside Java read the same documents.
- Good, because editors complete and check documents through the JSON Schema, with no plugin to maintain.
- Good, because the model and its changes are reviewed as plain text diffs.
- Bad, because errors that need the whole model (an unknown supertype, a cycle) are found when the build step or the registry validates, not as you type.
- Bad, because renames do not propagate automatically between documents and the Java code that uses the generated types.
- Bad, because YAML has pitfalls (indentation, values that look like other types), which the strict subset and the JSON Schema only partly contain.
- Bad, because Cairn needs a YAML 1.2 parser at runtime, and a code generator to maintain.

### Confirmation

- Every origin and schema definition converts to a document and back unchanged, checked by tests over generated definitions.
- The reference domain is also kept as a schema document, which the tests read. The [reference domain page](../reference/reference-domain.md) stays canonical, and a test checks the document against it, as one checks the test fixture today.
- The published JSON Schema accepts every example document in the docs and tests, and rejects the documents of the negative tests.
- CI compares an application's documents with its last release and fails on incompatible changes without a migration.

## Pros and Cons of the Options

### Schema documents in JSON only

- Good, because JSON is strict: no indentation rules, and no values that look like other types.
- Good, because tools and agents produce JSON natively.
- Bad, because JSON has no comments, and long documents are harder to read and review in pull requests.
- Bad, because it saves nothing over YAML 1.2, which already reads every JSON document.

### Annotated Java interfaces, processed at compile time

The first version of this ADR proposed this option.

- Good, because types are plain Java, with full editor support and model errors shown at compile time.
- Good, because there is no document format to parse.
- Bad, because a new type needs a compiler, a build and a release, so agents cannot add types to a running store.
- Bad, because the model is written in Java only. Other languages consume an export.
- Bad, because an annotation processor must work with incremental builds in every editor.

### An existing modeling language

- LinkML is YAML-based, with classes, mixins and slots, and generates JSON Schema and code.
- GraphQL SDL is widely known, with interfaces and types.
- Smithy and Pkl are typed languages for models and configuration, with their own tools.
- Good, because they come with documentation, tools and users.
- Bad, because none of them knows Cairn's concepts: multiplicities on both relationship ends, composition, inverse names, aliases, origins and time. Extensions or conventions would carry them, and the real model would hide in those.
- Bad, because their evolution is driven by other needs, and Cairn would follow it.

### A schema language of our own

- Good, because it can be the most concise and readable.
- Bad, because we maintain a parser, a formatter and editor plugins, and agents know it less well than YAML or JSON.

### Only a programmatic schema API

- Good, because it is the simplest to build, and the documents need it anyway.
- Bad, because it is verbose, needs Java, and gives no typed access to data.

## More Information

- Revised on 2026-10-13 while still proposed, then accepted. The first version chose annotated Java interfaces processed at compile time; the AI-native requirement of [ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md) changed the outcome. The file name keeps the first title.
- A second revision on the same day replaced two notations, YAML for people and JSON for tools, with one format: YAML 1.2, which includes JSON.
- How the decision was built: `add-self-describing-schema` added descriptions, examples, constraints and rule references to the definitions (planned here for `add-schema-documents`), and `add-schema-documents` added the format. A round trip gives back an equal definition, except that a number written for an attribute of a wider value type comes back in that type.
- Validation logic that a document cannot declare is written in Java: [ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md).
- JSON Schema: <https://json-schema.org/>
- YAML 1.2: <https://yaml.org/spec/1.2.2/>
- LinkML: <https://linkml.io/>
