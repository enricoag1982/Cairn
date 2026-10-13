# Tasks

## 1. Build setup

- [x] 1.1 Record the build-tool decision: Gradle (ADR-0015, accepted), with ADR-0014 (Maven) rejected; verify the decision log shows both
- [x] 1.2 Add the Gradle Wrapper, `settings.gradle.kts`, the version catalog `gradle/libs.versions.toml`, the convention plugin `sysrec.java-library` in `build-logic/` (Java 25 toolchain, JUnit 6 and AssertJ for tests, tests run with `-XX:+UseCompactObjectHeaders`, Javadoc checks), `gradle.properties` with the build and configuration caches on, and the subprojects `sysrec-api` and `sysrec-core`, each with a `module-info.java`; verify `./gradlew build` succeeds
- [x] 1.3 Add Gradle and Java entries to `.gitignore` and `.editorconfig`; verify `git status` shows no build output after `./gradlew build`
- [x] 1.4 Add a CI job that runs `./gradlew build` on Java 25 and on the newest Java release, after confirming the new CI job with the user; verify the job passes on the pull request
- [x] 1.5 Finish ADR-0005's checklist: replace the build line and the status line in `AGENTS.md` with the exact commands and current state, update the build-files boundary (with the user's approval) and the build line in `openspec/config.yaml`, update the build notes in `README.md`, and add the build to the architecture constraints; verify `npm run check` passes

## 2. Definition format (`sysrec-api`)

- [x] 2.1 Add `ValueType`, `Multiplicity` with its four constants, `Range` with typed literals, and `AttributeDefinition` (required, unique, range, aliases, deprecated); verify with unit tests that constructors reject nulls and minimums or maximums outside 0/1 and 1/unbounded
- [x] 2.2 Add `EntityTypeDefinition`, `TraitDefinition`, `RelationshipTypeDefinition` (ends, both multiplicities, inverse name and aliases, composition or association, symmetric, supertype, attributes, deprecated) and `SchemaDefinition`; verify equality and immutability with unit tests
- [x] 2.3 Add the fluent builder, and a test fixture that builds the complete reference domain model of `docs/reference/reference-domain.md`; verify the fixture test checks every type, attribute and relationship type in that page
- [x] 2.4 Write Javadoc for every public type, naming the spec capability it serves; verify `./gradlew build` runs Javadoc without warnings

## 3. Validation (`type-model`)

- [x] 3.1 Add `Problem`, `ProblemCode`, the path format and the phased validator skeleton (design D3); verify with the scenario test "Several problems at once"
- [x] 3.2 Implement the name rules (syntax, uniqueness across kinds, inverse names, aliases counted as names); verify with the scenario tests "Duplicate type name", "Names that differ only in case", "Inverse name equal to a relationship type name" and "Invalid characters"
- [x] 3.3 Implement reference resolution and the hierarchy rules (supertype kind, cycles, traits not allowed as supertypes or relationship ends); verify with the scenario tests "Abstract supertype with concrete subtypes", "Supertype that is not an entity type", "Cycle in supertypes", "Attributes come from a trait", "Trait reached twice" and "Trait as a relationship end"
- [x] 3.4 Implement the attribute rules (name conflicts across supertypes and traits, ranges); verify with the scenario tests "Name declared twice in a hierarchy", "Attribute declared twice by one type", "Same name from two traits", "Range on an unordered value type", "Range bound of the wrong value type", "Empty range" and "Valid range"
- [x] 3.5 Implement the relationship rules (multiplicity, composition, symmetry, ends of relationship subtypes); verify with the scenario tests "Relationship to an abstract type", "Exactly one target per source", "Composition with shared targets", "Symmetric relationship between different types" and "Relationship subtype with an unrelated target"
- [x] 3.6 Verify the reference model validates without problems with the scenario test "Defining the reference model without classes"

## 4. Resolved schema (`type-model`)

- [x] 4.1 Compile a valid definition into the immutable `Schema` (supertype chains, transitive subtypes, effective attributes with their declaring element, name and alias lookup); verify with the scenario tests "Subtypes of an abstract type", "Declaring element of an inherited attribute" and "Deprecated attribute is reported"

## 5. Registration and history (`schema-evolution`)

- [x] 5.1 Add `Origin` with version parsing and comparison; verify with unit tests that `1.10.0` sorts after `1.9.0`, and with the scenario test "Malformed origin"
- [x] 5.2 Add the in-memory `SchemaRegistry`: serialized registration, injected `InstantSource`, microsecond truncation, strictly increasing transaction times, basis counter and immutable published history; verify with the scenario tests "First registration" and "Invalid definition", and a test with a frozen clock that transaction times still increase
- [x] 5.3 Assign identifiers and match elements by name and alias (design D5); verify with the scenario test "Identifiers survive later registrations" and a test that an attribute moved to a supertype keeps its identifier
- [x] 5.4 Detect unchanged registrations and read history by basis and by transaction time; verify with the scenario tests "Registering the same model twice", "Same model in a different order", "Reading an earlier version" and "Before any registration"
- [x] 5.5 Record origins on types, allow references to other origins and reject redefinitions; verify with the scenario tests "Referencing another origin's types" and "Redefining another origin's type"

## 6. Compatibility (`schema-evolution`)

- [x] 6.1 Implement the classified diff over matched elements, where an unclassified property change is breaking; verify with a unit test that an unknown property change is reported as breaking
- [x] 6.2 Implement the compatible-change rules; verify with the scenario tests "Adding an optional attribute", "Widening a value type" and "Relaxing a multiplicity", plus one test for each other compatible change listed in the spec
- [x] 6.3 Implement the breaking-change rules; verify with the scenario tests "Removing an attribute", "Renaming without an alias", "Making an attribute required", "Including a trait with a unique attribute", "Widening a required relationship end" and "Several breaking changes", plus one test for each other breaking change listed in the spec
- [x] 6.4 Implement version rules: a changed definition needs a higher version, and a lower version is "unchanged" when the stored definition is a compatible evolution of it; verify with the scenario tests "Rolling back to an earlier release", "Lower version that is not a predecessor" and "Changed definition with the same version"
- [x] 6.5 Resolve aliases in schema lookups; verify with the scenario test "Looking up an old name"

## 7. Documentation

- [x] 7.1 Check the glossary entries that this proposal added (alias, breaking change, compatible change, deprecated, inverse name, multiplicity, origin, problem, schema definition, schema identifier, schema transaction, symmetric relationship type, value type) against the implemented behavior and correct them; verify `npm run docs:check` passes
- [x] 7.2 Describe the `sysrec-api` and `sysrec-core` building blocks and their capabilities in `docs/architecture/README.md` and bump `last-reviewed`; verify `npm run docs:check` passes
- [ ] 7.3 Before archiving, ask the user to accept or reject ADR-0007, ADR-0008, ADR-0010 and ADR-0012, which this change relies on; update their status and run `npm run docs:index`. If one is rejected, update the delta specs first. Verify the decision log
- [x] 7.4 Run `npm run check` and `./gradlew build`, then delegate a drift review to the `docs-reviewer` subagent and fix its findings; verify both commands pass and the review reports no open drift
