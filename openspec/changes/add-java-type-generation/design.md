# Design

## Context

ADR-0012 decided that Java types are generated from schema documents by a Gradle plugin, and that the engine never depends on them. ADR-0021 decides their shape: interfaces, snapshot records, builders of writes and rule base classes. This design settles the modules, the generator, the plugin and how they are tested. The requirements are in `specs/java-types/spec.md`.

## Goals / Non-Goals

**Goals:**

- A generator that is plain Java, testable without Gradle, with a command line.
- A Gradle plugin that works whatever Java runs Gradle, and that keeps builds incremental.
- Typed tests of the generated reference domain types against the in-memory store.

**Non-Goals:**

- The typed query model and typed navigation (`add-queries-and-aggregation`).
- Publishing artifacts.

## Decisions

### D1. Modules

- `cairn-codegen`: module `io.github.enricoag1982.cairn.codegen`, exporting that package. It depends on `cairn-documents` (reading documents, locating problems) and `cairn-core` (checking documents together with an in-memory schema registry). The generated code itself depends on `cairn-api` only.
- `cairn-gradle-plugin`: the plugin, package `io.github.enricoag1982.cairn.gradle`, plugin ID `io.github.enricoag1982.cairn.types`. It has no dependency on Cairn's classes, which need Java 25, and is compiled for Java 17, the oldest Java that Gradle 9 runs on. It has no `module-info.java`, as Gradle plugins load from the class path.
- `build-logic` gains two convention plugins: `cairn.gradle-plugin` (the `java-gradle-plugin` plugin, release 17, the same lint and Javadoc settings as `cairn.java-library`, and TestKit tests that get the generator's class path and `cairn-api`'s from a configuration), and `cairn.reference-types` (a task that runs the generator of the project's own main classes on `docs/reference/reference-model.cairn.yaml` into a generated test source directory, package `io.github.enricoag1982.cairn.reference.types`).

### D2. `cairn-api` additions

- `TypedEntity` (`EntityState entityState()`, default `EntityId id()`) and `TypedRelationship` (`RelationshipState relationshipState()`, default `id()`, `source()`, `target()`) in package `data`.
- `TypedValues`, a final class of static methods that generated code uses to read the values of a state: `string(values, attribute)` and `optionalString(values, attribute)`, and the same for `bool`, `int`, `long`, `decimal`, `date` and `instant`. A missing required value, or a value of another type, throws `IllegalArgumentException` naming the attribute. Writing uses the existing `Value.of`.

### D3. What is generated

For package `P`, every file starts with a comment that names the generator and the documents, and says not to edit it.

- A trait `R`: `public interface R extends TypedEntity` with its attributes' accessors.
- An entity type `T` with supertype `S` and traits `R…`: `public interface T extends S, R…` (or `TypedEntity` without either), with:
  - `String TYPE = "T"` and its own attributes' accessors, with Javadoc from the document's descriptions, allowed values, patterns and lengths
  - `static T from(EntityState state)`: a `switch` on the state's type over `T` and every generated subtype of `T`, each to its snapshot; a generated type outside that set throws `IllegalArgumentException`; any other type reads as `T`'s snapshot
  - `record Snapshot(EntityState entityState, <every attribute of T, inherited first>) implements T`, with a static factory that reads the values with `TypedValues`
  - for a type that is not abstract, `static Create create()`, and for every type `static Patch patch(Ref target)` and `static Replace replace(Ref target)`: final nested builder classes with one setter per attribute (taking the Java type, never `Optional`), `as(String temporaryId)` and `id(EntityId id)` on `Create`, `clear<Attribute>()` for optional attributes and `check(ConflictCheck)` on `Patch`, and the terminal methods `effective(EffectivePeriod)` and `effectiveFrom(Instant)`, which return the `Write`
- A relationship type `Q` with supertype `U`: `public interface Q extends U` (or `TypedRelationship`), the same members with `RelationshipState`, and `static Relate relate(Ref source, Ref target)` plus `static Relate relate(<Source> source, <Target> target)` taking the generated types of its ends.
- A rule `name` version `v` that the documents name: `public abstract class <Name>RuleV<v> implements EntityRule` (or `RelationshipRule`), where `<Name>` is the rule name in Pascal case (`lei-checksum` becomes `LeiChecksum`). It has `NAME`, `VERSION` and `APPLIES_TO` constants, `protected static RuleDescriptor describe(String description, RuleCode... codes)`, a final `check(EntityState, RuleContext)` that reads the version with `T.from`, and `protected abstract void check(T value, RuleContext context)`. The type is the one that names the rule, or the closest common supertype of the types that name it; if they have none, the first in document order, and the documents are rejected as a registry would reject them.
- Deprecated types and attributes are `@Deprecated`; generated code that refers to them suppresses the deprecation warning, so that only an application's own uses warn.

### D4. Names

- Java keywords, the literals `true`, `false` and `null`, and the names of members that generated code adds (`entityState`, `relationshipState`, `id`, `source`, `target`, `from`, `create`, `patch`, `replace`, `relate`, and on builders `as`, `check`, `effective`, `effectiveFrom`) get the suffix `_` where they would clash.
- Types are referred to by simple name. Where a type of the documents has the same simple name as a library type that a file uses, the file uses that library type's qualified name. A type name that clashes with a nested name of its own (`Snapshot`, `Create`, `Patch`, `Replace`, `Relate`) gets the suffix `_` on the nested name.

### D5. The generator

- `JavaTypes.generate(List<Path> documents, String packageName)` returns either the generated files (relative path and text) or the located problems. `CairnCodegen.main` takes `--package <name> --output <dir> <document>...`, writes the files (replacing the output directory's content), prints problems as `<file>:<line>:<column>: <code> <path>: <message>` to standard error, and exits with 1 on problems and 2 on wrong options.
- Checking: the documents are registered in an in-memory schema registry that declares, without code, every rule they name, with the closest common supertype as its type. Documents that refer to each other are registered in passes until a pass registers nothing; the problems of the documents left are reported. The closest common supertype is computed once, in `cairn-documents`, and `cairn-agent-tools` uses the same code for the rules its server declares.
- Output is sorted and has no time stamps, so it is the same for the same documents.

### D6. The Gradle plugin

- Applied to a project with the `java` plugin, it adds the extension `cairn` with `schemaDocuments` (a file collection, by default `src/main/cairn/**/*.cairn.yaml` and `*.cairn.json`) and `packageName` (required), the configuration `cairnCodegen` (the generator's class path; nothing is published yet, so a project declares it), and the cacheable task `generateCairnTypes`, which writes to `build/generated/sources/cairn/java/main`, a source directory of the main source set.
- The task runs the generator with `ExecOperations.javaexec`, with the Java 25 launcher from Gradle's toolchains, so the generator never loads into Gradle's own JVM. Its inputs are the documents (relative paths and content), the package and the generator's class path; its output is the directory. The plugin works with the configuration cache, which this repository turns on.

### D7. Tests

- `cairn-codegen` tests generate code for the reference model and for documents with awkward names, compile it in the test with `javax.tools` against `cairn-api` with `-Xlint:all -Werror`, and check the generated text where it matters.
- `cairn-codegen` also applies `cairn.reference-types`, so its typed tests use the generated reference types with the in-memory store and the reference stories, including a `lei-checksum` written against its generated base class.
- `cairn-gradle-plugin` tests use Gradle TestKit with a project in a temporary directory, which gets the generator's and `cairn-api`'s jars through files.

## Risks / Trade-offs

- [The generator and the engine need Java 25, Gradle may run on older Java] → The plugin forks a Java 25 process; a build without a Java 25 toolchain fails with Gradle's toolchain message.
- [Renames in a document break the Java code that uses the generated names] → As ADR-0012 says; aliases keep stored data readable, and the compiler shows every use to change.
- [TestKit tests are slow] → Few of them: the plugin is thin, and the generator is tested directly.
