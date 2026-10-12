# Tasks

## 1. Build setup (blocked until ADR-0014 is accepted)

- [ ] 1.1 Get the user's decision on ADR-0014 (Maven), set its status, run `npm run docs:index`, and verify the decision log shows the final status
- [ ] 1.2 Add the Maven Wrapper and a root `pom.xml` (release 25, pinned plugin versions, JUnit 5 and AssertJ in test scope, tests run with `-XX:+UseCompactObjectHeaders`) with the modules `sysrec-api` and `sysrec-core`, each with a `module-info.java`; verify `./mvnw verify` succeeds
- [ ] 1.3 Add Java and Maven entries to `.gitignore` and `.editorconfig`; verify `git status` shows no build output after `./mvnw verify`
- [ ] 1.4 Add a CI job that runs `./mvnw verify` on Java 25 and on the newest Java release, after confirming the new CI job with the user; verify the job passes on the pull request
- [ ] 1.5 Finish ADR-0005's checklist: replace the build line and the status line in `AGENTS.md` with the exact commands and current state, update the build-files boundary (with the user's approval) and the language line in `openspec/config.yaml`, update the build notes in `README.md`, and add the build to the architecture constraints; verify `npm run check` passes

## 2. Definition format (`sysrec-api`)

- [ ] 2.1 Add `ValueType`, `Multiplicity` with its four constants, `Range` with typed literals, and `AttributeDefinition` (required, unique, range, aliases, deprecated); verify with unit tests that constructors reject nulls and minimums or maximums outside 0/1 and 1/unbounded
- [ ] 2.2 Add `EntityTypeDefinition`, `TraitDefinition`, `RelationshipTypeDefinition` (ends, both multiplicities, inverse name and aliases, composition or association, symmetric, supertype, attributes, deprecated) and `SchemaDefinition`; verify equality and immutability with unit tests
- [ ] 2.3 Add the fluent builder, and a test fixture that builds the complete reference domain model of `docs/reference/reference-domain.md`; verify the fixture test checks every type, attribute and relationship type in that page
- [ ] 2.4 Write Javadoc for every public type, naming the spec capability it serves; verify `./mvnw verify` runs Javadoc without warnings

## 3. Validation (`type-model`)

- [ ] 3.1 Add `Problem`, `ProblemCode`, the path format and the phased validator skeleton (design D3); verify with the scenario test "Several problems at once"
- [ ] 3.2 Implement the name rules (syntax, uniqueness across kinds, inverse names, aliases counted as names); verify with the scenario tests "Duplicate type name", "Names that differ only in case", "Inverse name equal to a relationship type name" and "Invalid characters"
- [ ] 3.3 Implement reference resolution and the hierarchy rules (supertype kind, cycles, traits not allowed as supertypes or relationship ends); verify with the scenario tests "Abstract supertype with concrete subtypes", "Supertype that is not an entity type", "Cycle in supertypes", "Attributes come from a trait" and "Trait as a relationship end"
- [ ] 3.4 Implement the attribute rules (name conflicts across supertypes and traits, ranges); verify with the scenario tests "Name declared twice in a hierarchy", "Same name from two traits", "Range on an unordered value type" and "Valid range"
- [ ] 3.5 Implement the relationship rules (multiplicity, composition, symmetry, ends of relationship subtypes); verify with the scenario tests "Relationship to an abstract type", "Exactly one target per source", "Composition with shared targets", "Symmetric relationship between different types" and "Relationship subtype with an unrelated target"
- [ ] 3.6 Verify the reference model validates without problems with the scenario test "Defining the reference model without classes"

## 4. Resolved schema (`type-model`)

- [ ] 4.1 Compile a valid definition into the immutable `Schema` (supertype chains, transitive subtypes, effective attributes with their declaring element, name and alias lookup); verify with the scenario tests "Subtypes of an abstract type", "Declaring element of an inherited attribute" and "Deprecated attribute is reported"

## 5. Registration and history (`schema-evolution`)

- [ ] 5.1 Add `Origin` with version parsing and comparison; verify with unit tests that `1.10.0` sorts after `1.9.0`, and with the scenario test "Malformed origin"
- [ ] 5.2 Add the in-memory `SchemaRegistry`: serialized registration, injected `InstantSource`, microsecond truncation, strictly increasing transaction times, basis counter and immutable published history; verify with the scenario tests "First registration" and "Invalid definition", and a test with a frozen clock that transaction times still increase
- [ ] 5.3 Assign identifiers and match elements by name and alias (design D5); verify with the scenario test "Identifiers survive later registrations" and a test that an attribute moved to a supertype keeps its identifier
- [ ] 5.4 Detect unchanged registrations and read history by basis and by transaction time; verify with the scenario tests "Registering the same model twice", "Reading an earlier version" and "Before any registration"
- [ ] 5.5 Record origins on types, allow references to other origins and reject redefinitions; verify with the scenario tests "Referencing another origin's types" and "Redefining another origin's type"

## 6. Compatibility (`schema-evolution`)

- [ ] 6.1 Implement the classified diff over matched elements, where an unclassified property change is breaking; verify with a unit test that an unknown property change is reported as breaking
- [ ] 6.2 Implement the compatible-change rules; verify with the scenario tests "Adding an optional attribute", "Widening a value type" and "Relaxing a multiplicity", plus one test for each other compatible change listed in the spec
- [ ] 6.3 Implement the breaking-change rules; verify with the scenario tests "Removing an attribute", "Renaming without an alias", "Making an attribute required" and "Several breaking changes", plus one test for each other breaking change listed in the spec
- [ ] 6.4 Implement version rules: a changed definition needs a higher version, and a lower version is "unchanged" when the stored definition is a compatible evolution of it; verify with the scenario tests "Rolling back to an earlier release", "Lower version that is not a predecessor" and "Changed definition with the same version"
- [ ] 6.5 Resolve aliases in schema lookups; verify with the scenario test "Looking up an old name"

## 7. Documentation

- [ ] 7.1 Check the glossary entries that this proposal added (alias, breaking change, compatible change, deprecated, inverse name, multiplicity, origin, problem, schema definition, schema identifier, schema transaction, symmetric relationship type, value type) against the implemented behavior and correct them; verify `npm run docs:check` passes
- [ ] 7.2 Describe the `sysrec-api` and `sysrec-core` building blocks and their capabilities in `docs/architecture/README.md` and bump `last-reviewed`; verify `npm run docs:check` passes
- [ ] 7.3 Before archiving, ask the user to accept or reject ADR-0007, ADR-0008, ADR-0010 and ADR-0012, which this change relies on; update their status and run `npm run docs:index`. If one is rejected, update the delta specs first. Verify the decision log
- [ ] 7.4 Run `npm run check` and `./mvnw verify`, then delegate a drift review to the `docs-reviewer` subagent and fix its findings; verify both commands pass and the review reports no open drift
