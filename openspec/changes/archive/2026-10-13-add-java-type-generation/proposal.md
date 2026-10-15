# Proposal

## Why

Types are defined in schema documents (ADR-0012), and so far applications reach their data only through the generic API: maps of values keyed by attribute names, checked at commit.
That suits agents and types registered at runtime, but Java applications and rules want typed access: a `Person` with a `name()`, a builder that knows Person's attributes, a rule that checks an `Organization`.
ADR-0012 promises Java types generated from documents by a Gradle plugin; this change delivers them, in the shape that ADR-0021 decides.

## What Changes

- A generator that reads schema documents, checks them together, and writes Java source for them:
  - an interface per entity type, trait and relationship type, with an accessor per attribute, extending the interfaces of its supertype and traits
  - a snapshot record per entity and relationship type, read from the generic state with `from`
  - builders for creating, relating, patching and replacing, which produce the writes of the generic API
  - an abstract base class for every rule the documents name, with a typed check
- `cairn-api` gains the two interfaces every generated type extends, `TypedEntity` and `TypedRelationship`, and the conversions that generated code uses to read values.
- The generator runs from the command line, and from a Gradle plugin that generates the types of a project's schema documents before its Java code compiles.
- Problems in the documents fail the generation, located in the documents, and nothing is written.

### Non-goals

- The typed query model of ADR-0012, and typed navigation: they come with `add-queries-and-aggregation`.
- Publishing the generator and the plugin to a repository. They are built and tested in this repository; publishing comes when Cairn is released.
- Generating schema definitions as Java code. Applications register their documents with `cairn-documents`.

## Capabilities

### New Capabilities

- `java-types`: the Java types generated from schema documents, how they read states and build writes, rule base classes, names, and the generator with its Gradle plugin

### Modified Capabilities

None. Generated code uses the existing generic API; the engine does not depend on it.

## Impact

- **Decisions:** ADR-0012 (generated Java types; the engine never depends on them), ADR-0018 (rules in Java), ADR-0010 (inheritance and traits) and ADR-0021 (the shape of the generated types), all accepted. ADR-0021 was accepted under the standing approval, with this proposal.
- **Code:** two new subprojects: `cairn-codegen`, the generator with a command-line entry point, and `cairn-gradle-plugin`, the plugin. Two convention plugins in `build-logic/`: one for Gradle plugins, which run on the Java that runs Gradle, and one that generates the reference domain's types for tests. `cairn-api` gains `TypedEntity`, `TypedRelationship` and `TypedValues`. `cairn-documents` gains `NamedRules`, which finds the type each named rule applies to; the agent server in `cairn-agent-tools` now uses it for its declared rules.
- **Dependencies:** none from outside. The plugin uses Gradle's API and TestKit, which Gradle provides.
- **Docs:** a guide for generating types and writing rules against them, the glossary (generated type, snapshot record, rule base class), the architecture page, the status lines of `AGENTS.md` and `README.md`, and the context of `openspec/config.yaml`.
