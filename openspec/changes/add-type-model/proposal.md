# Proposal

## Why

Every other Cairn capability depends on a meta-model to validate against: entity versions, relationships, queries and the Java type definitions. None exists yet.
Building it first gives the later changes a stable, testable schema API. It also pins down the compatibility rules before any data exists, while they are still cheap to change.

## What Changes

- A programmatic schema API that describes a schema as plain data:
  - entity types: concrete or abstract, with at most one entity supertype, any number of traits, and attributes
  - traits
  - attributes, each with a value type and optional constraints (required, unique, range)
  - deprecation marks on types and attributes
  - relationship types: source and target entity types, a multiplicity at each end, an inverse name, composition or association, an optional symmetric mark, attributes, and at most one relationship supertype
- Validation of a schema definition that reports every problem at once, with its location.
- Registration of a schema definition, submitted with an origin (a name and a version), as a schema transaction with a transaction time and a basis. The schema is stored as data. Its history is readable as of any earlier transaction time. Every type and attribute gets a stable identifier.
- Compatibility checking against the stored schema:
  - compatible changes are applied
  - breaking changes are rejected with every reason listed
  - renames are compatible when the old name is kept as an alias
  - an older model version from the same origin is accepted without change, which makes rollbacks possible
- The first Java code, which needs the build to be set up first: Java 25 (ADR-0005), built with Gradle (ADR-0015).

### Non-goals

- Schema documents in YAML and JSON, which come with `add-schema-documents`, and generated Java types, which come with `add-java-type-generation` (ADR-0012)
- Entities and relationships as data: creating, versioning or reading them, polymorphic queries over them, and integrity checks on them
- Migrations for breaking changes, and default values or backfills
- Durable storage of the schema: it is kept in memory until the commit log exists (`add-durable-commit-log`)
- Tools for AI agents and people to define types in a running store, which come with `add-agent-tools` (ADR-0017). The programmatic schema API built here is what they will call
- Multi-valued attributes, structured values, and constraints spanning several entities
- Effective time for the schema: the schema has history in transaction time only (ADR-0010)

## Capabilities

### New Capabilities

- `type-model`: what a schema can define (entity types, traits, attributes, relationship types, names) and how a schema definition is validated
- `schema-evolution`: registering a schema with an origin, stable identifiers, history in transaction time, and the compatibility rules for changing it

### Modified Capabilities

None. No specs exist yet.

## Impact

- **Decisions:** builds on ADR-0010 and ADR-0012, on the transaction time of ADR-0008, and on the single writer of ADR-0007. All four are still `proposed`, so this change depends on undecided ADRs: they must be accepted, or this change revised, before it is archived. ADR-0011 is context only: this change does not build the storage interface. The build uses Gradle (ADR-0015, accepted).
- **Code:** the first source tree: a Gradle multi-project build with `cairn-api` (public types) and `cairn-core` (validation, compatibility, the in-memory schema store).
- **Dependencies:** test-only, JUnit 6 and AssertJ. Nothing at runtime.
- **CI:** a new job that builds and tests the Java code on Java 25 and on the newest Java release, as ADR-0005 recommends.
- **Docs:** `AGENTS.md` commands, the architecture page (building blocks), the glossary (new terms), and the decision log.
