# Design

## Context

This change adds the first code to the repository. See `proposal.md` for why; the requirements are in `specs/type-model/spec.md` and `specs/schema-evolution/spec.md`.

Constraints that shape the approach:

- Java 25 (ADR-0005) and Gradle with the Kotlin DSL (ADR-0015), both accepted: convention plugins in `build-logic/`, a version catalog, and a Java 25 toolchain.
- The meta-model follows ADR-0010 (proposed): single inheritance, traits, first-class relationship types, and the schema stored as data with history in transaction time.
- Types will be written in schema documents, in YAML or JSON (ADR-0012, proposed, revised on 2026-10-13 for ADR-0017). Documents will be read into the definition format built here, so it must stay plain data that any source can produce: a document, a tool or an AI agent.
- Registration is serialized because of the single writer of ADR-0007 (proposed).
- The storage interface of ADR-0011 does not exist yet, so the schema lives in memory in this change. ADR-0011 is context only; this change does not depend on it.
- Transaction time and basis follow ADR-0008 (proposed): UTC instants with microsecond precision, strictly increasing.
- Small API types are immutable records that never rely on identity, as ADR-0005 recommends.

## Goals / Non-Goals

**Goals:**

- A definition format that later changes extend compatibly, without changing what exists.
- Validation and compatibility checking as pure functions over definitions, independent of storage.
- An in-memory schema registry with history in transaction time, shaped so that it can move onto the commit log and key-value store later without changing its API.
- One test per spec scenario, built on the reference domain.

**Non-Goals:**

- Encoding definitions as text. `add-schema-documents` defines the YAML and JSON format (ADR-0012).
- Concurrency beyond the single-writer rule. Registrations are serialized; readers use immutable snapshots.
- Performance work. Schemas are small (hundreds of types).

## Decisions

### D1. Two Gradle subprojects, using Java modules

- `cairn-api` holds the public types: definitions, the resolved schema, registration results and problems. It has no dependencies.
- `cairn-core` holds the validator, the compatibility checker and the in-memory schema registry. It depends on `cairn-api`.
- Both have a `module-info.java`. Core exports only its entry point, not its internals, so internals cannot become API by accident.
- The convention plugin `cairn.java-library` in `build-logic/` configures both subprojects: the Java 25 toolchain, JUnit 6, compact object headers for tests, and Javadoc checks. CI tests the newest Java release by overriding the toolchain version through a Gradle property.
- A second convention plugin, `cairn.reference-domain-tests`, makes the tests of `cairn-api` rerun when the reference domain page changes.
- The publishing group and the base package are `io.github.enricoag1982.cairn` (ADR-0016), with `...cairn.schema` for the API and `...cairn.core` for the engine.
- Alternatives:
  - a single module, which is simpler but lets internals leak into the API
  - no Java modules, which avoids friction with test tooling but gives a weaker boundary. We fall back to this if the friction outweighs the benefit; the change is local.

### D2. Definitions are immutable records that refer to each other by name

- `SchemaDefinition` lists the `EntityTypeDefinition`, `TraitDefinition` and `RelationshipTypeDefinition` records, each with its `AttributeDefinition`s.
- Supporting values:
  - `ValueType` is an enum: `STRING`, `BOOLEAN`, `INT`, `LONG`, `DECIMAL`, `DATE`, `INSTANT`.
  - `Multiplicity` holds a minimum (0 or 1) and a maximum (1 or unbounded), with the constants `ZERO_OR_ONE`, `EXACTLY_ONE`, `ZERO_OR_MORE` and `ONE_OR_MORE`.
  - `Range` has an optional lower and upper bound. Each bound is inclusive or exclusive, and holds a typed literal: a sealed interface with one record per orderable value type.
- References between definitions (supertype, traits, relationship ends) are names, not object references.
  - This keeps definitions plain data that any source can produce, including references to another origin's types.
  - Unknown names become validation problems instead of construction errors.
- A fluent builder makes definitions pleasant to write by hand, for example `.entityType("Organization", t -> t.supertype("Party").attribute("lei", STRING, a -> a.unique()))`.
- Record constructors only reject nulls, and `Multiplicity` rejects bounds it cannot express (anything but 0 or 1, and 1 or unbounded). Every rule from the specs is checked by the validator, so all problems can be reported at once.
- Alternative: definitions holding object references. Rejected, because they cannot express references to other origins' types or invalid references.

### D3. Validation is a pure function that returns every problem

- The validator takes one combined `SchemaDefinition`, with the types of every origin, and returns a list of problems. It throws nothing.
- Each `Problem` has a stable `ProblemCode` (an enum), a path (`Organization`, `Organization.lei`, `Owns.ownedBy`) and a message.
- It runs in phases:
  1. names and syntax
  2. resolving references, including the kind of each supertype, included trait and relationship end
  3. hierarchies: cycles
  4. effective attribute sets: name conflicts, ranges
  5. relationship rules: composition, symmetry, the ends of relationship subtypes
- A later phase skips only the elements that an earlier phase found broken, so one mistake does not cause a cascade of follow-up problems. Everything else is still checked.
- Alternative: an exception at the first error. Rejected, because the spec requires every problem.

### D4. A resolved, immutable `Schema`

- A valid definition is compiled into an immutable `Schema`. For each entity and relationship type it holds:
  - its identifier, origin and supertype chain (nearest first)
  - its subtypes, transitively
  - its effective attributes, each with the type or trait that declares it
- Name and alias lookups use maps built once.
- The registry returns `Schema` objects for every schema query.

### D5. Store-local identifiers, matched by name and alias

ADR-0010 (proposed) decides that schema identifiers are local to the store, never change and are never reused, and that stored data refers to them instead of names. This section covers only how they are represented and matched.


- `TypeId` and `AttributeId` are records wrapping a `long`. They are assigned from one counter per store and never reused. Entity types, traits and relationship types share the `TypeId` space.
- Between two versions of an origin, elements are matched in this order:
  1. type by name, then by the aliases of the new version
  2. attribute by name or alias, within the effective attributes of the type that declared it before
- Rule 2 means an attribute moved up to a supertype keeps its identifier.
- Identifiers never leave a store. Exported models carry names.
- Alternative: UUIDs. Rejected as unnecessary; sequence numbers are compact and deterministic in tests.

### D6. Compatibility is a classified diff

- The checker compares the stored schema of an origin with the candidate, using the matching of D5. It returns a list of changes, each with a path, a reason and a classification, compatible or breaking.
- There is one rule per property, following the lists in the `schema-evolution` spec.
- A property change that no rule covers is breaking, as the spec says. A forgotten rule therefore errs on the safe side.
- For a lower version of the same origin (a rollback), the checker runs in the other direction. If moving from the submitted definition to the stored one is compatible, the registration is "unchanged".

### D7. The in-memory schema registry

- The API is a `SchemaRegistry` interface:
  - `register(Origin, SchemaDefinition)` returns a sealed `RegistrationResult`: `Applied` (transaction time, basis, schema), `Unchanged` (schema) or `Rejected` (problems).
  - `validate(Origin, SchemaDefinition)` checks a definition without registering it.
  - `current()`, `asOf(Basis)`, `asOf(Instant)` and `history()` read the schema.
- `register` is serialized, because it is the single writer (ADR-0007). History is an immutable list of (basis, transaction time, origin, schema), published through a volatile reference, so readers never lock.
- Transaction time comes from an injected `java.time.InstantSource` and is truncated to microseconds. If the clock has not moved forward, the next microsecond is used, so transaction times strictly increase.
- The basis is a `long` counter. It will be shared with data transactions once they exist.
- An `Origin` is a name and a version. The version is parsed into numbers and compared one number at a time.
- Each type records its origin, with the version of that origin's latest applied registration.
- In `add-durable-commit-log`, a schema transaction becomes a commit-log record and the schema is stored as data in the key-value store (ADR-0011). The `SchemaRegistry` API stays the same.
- Alternative: building directly on the key-value interface now. Rejected, because that interface does not exist yet, and building it here would pull ADR-0011's work into this change.

### D8. Tests

- JUnit 6 and AssertJ, as test-only dependencies.
- A fixture in the test fixtures of `cairn-api` (`src/testFixtures`) builds the reference domain model. `ReferenceModelTest` checks it against the reference domain page.
- Every scenario in the two specs has one test, whose display name is the scenario's name, so a scenario can be traced to its test.
- There is no conformance-suite module yet. Schema behavior does not depend on storage in this change, so the suite starts with `add-entity-versions`. The schema scenarios join the suite in `add-durable-commit-log`, when the schema moves onto the storage interface.

## Risks / Trade-offs

- [ADR-0007, ADR-0008, ADR-0010 and ADR-0012 are still proposed and may change at review; ADR-0012 already changed after the code was written, for ADR-0017] → Definitions stay plain data that refer to each other by name, so schema documents can be read into them, and `add-schema-documents` extends them compatibly.
- [Matching attribute identity for moved and renamed attributes is subtle] → Dedicated tests for moving up, renaming, and renaming plus moving.
- [Without migrations, the compatibility rules are strict: making an attribute required is always breaking] → Accepted until a later change adds defaults, backfills and migrations.
- [Java modules can cause friction with test tooling and, later, the code generator] → Drop `module-info.java` if it costs more than it protects (D1).
- [Identifiers differ between stores] → Models are exchanged by name. Identifiers are documented as local to a store.

## Migration Plan

None: there is no existing data and there are no users.

