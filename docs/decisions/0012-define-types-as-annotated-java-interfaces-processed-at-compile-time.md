---
status: proposed
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Define types as annotated Java interfaces processed at compile time

## Context and Problem Statement

Users define entity types, traits and relationship types ([ADR-0010](0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md)).
We need a way to write those definitions that feels natural to Java developers, can be reviewed like an API contract, and lets the engine check compatibility before a change reaches production.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Natural in any Java editor: autocompletion, navigation, refactoring
- Errors in the model are found at compile time
- The model can be reviewed and versioned like an API
- Typed Java access to data, without managed objects or sessions
- Types defined at runtime stay possible later

## Considered Options

- Annotated Java interfaces, processed at compile time
- A schema language (DSL) in its own files, generating Java
- Annotated Java records or classes, in the style of JPA
- Annotated Java interfaces, read by reflection at startup
- Only a programmatic schema API (builders)

## Decision Outcome

Proposed option: "Annotated Java interfaces, processed at compile time", because it gives full editor support and compile-time checks without a language of our own to maintain.

- Entity types, traits and relationship types are Java interfaces annotated `@EntityType`, `@Trait` and `@RelationshipType`. Accessor methods declare attributes. Annotations declare constraints (`@Unique`, `@Range`) and renames (`@Alias`).
- Inheritance uses `extends`. The annotation processor allows at most one `@EntityType` supertype; other parents must be traits.
- At compile time the annotation processor:
  - checks the model and reports errors in the editor
  - generates immutable implementations, builders and a typed query model
  - writes a model description with a fixed format into the jar
- At startup the engine registers the model description through the programmatic schema API, as a schema transaction checked for compatibility. CI compares the description with the last release.
- Generated types are values, not managed objects: a snapshot at a view's coordinates, with no lazy loading, change tracking or session. Every change goes through a transaction.
- A generic, untyped API reads and writes every type, for bulk loads and tools.
- The model lives in its own module, reviewed and versioned like an API. For consumers outside Java, the engine can export the stored schema (for example as JSON Schema or Avro).

**Types defined at runtime** are a follow-up. These rules keep them possible:

1. Every schema change goes through the programmatic schema API. The annotation processor's model description is one caller of it.
2. The engine works on the meta-model, by type and attribute identifiers, never on Java classes.
3. The generic API ships in the first version, and the conformance suite runs scenarios through both the typed and the generic API.
4. Each type records where it came from (a model jar and version, or runtime).
5. The model description format is the schema API's input format.
6. A typed query returns entities of unknown subtypes through their nearest known Java supertype, with extra attributes reachable through the generic API.

### Consequences

- Good, because types are plain Java with full editor support, and model errors appear at compile time.
- Good, because there is no parser, schema language or editor plugin to maintain.
- Good, because the generated types are immutable values that work the same with every storage implementation.
- Bad, because the model is written in Java only. Other languages consume an exported schema.
- Bad, because constraint annotations can pile up. Complex rules belong in code, not annotations.
- Bad, because we maintain an annotation processor, which must work with incremental builds in editors.

### Confirmation

- The annotation processor has tests for every rule it enforces.
- CI fails when the model description is incompatible with the last release and no migration is provided.

## Pros and Cons of the Options

### A schema language in its own files

- Good, because it is concise and independent of any programming language.
- Bad, because editors have no autocompletion, navigation or refactoring for it without a plugin, and we maintain a parser and code generator.

### Annotated records or classes

- Good, because they are familiar from JPA.
- Bad, because records cannot extend each other, so every record repeats its inherited attributes. Classes pull towards managed objects with sessions.

### Reflection at startup

- Good, because it needs no build step.
- Bad, because model errors only show at runtime, and deep reflection conflicts with the JDK's move to make `final` really final.

### Only a programmatic schema API

- Good, because it is the simplest to build, and tools need it anyway.
- Bad, because it is verbose to write and gives no typed Java access to data.

## More Information

- Similar annotation processors: Immutables, MapStruct and Hibernate's metamodel generator.
