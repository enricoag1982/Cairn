---
status: accepted
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Build the Java code with Gradle

## Context and Problem Statement

[ADR-0005](0005-choose-primary-implementation-language.md) chose Java 25 and left the build tool to its own decision.
[ADR-0014](0014-build-the-java-code-with-maven.md) proposed Maven; it was rejected in favor of this decision.
The first change that needs code, `add-type-model`, cannot start until the build exists.
The build will hold several modules (API, core engine, annotation processor, conformance suite, storage implementations) and must give fast feedback to people and AI agents.

## Decision Drivers

- Fast feedback from compile and test, as the number of modules grows
- Multi-module projects and annotation processors well supported
- Shared build configuration across modules without copy and paste
- Predictable builds that agents can read and edit reliably
- Mature tooling: editors, CI, publishing artifacts

## Considered Options

- Gradle with the Kotlin DSL
- Maven

## Decision Outcome

Chosen option: "Gradle with the Kotlin DSL", because incremental builds, the build cache and the configuration cache keep feedback fast as the engine grows, and convention plugins share configuration across modules.

- The Gradle Wrapper (`gradlew`) is committed, so contributors and CI use the same Gradle version.
- `settings.gradle.kts` at the repository root lists one subproject per building block, first `sysrec-api` and `sysrec-core`.
- Shared configuration lives in convention plugins in an included build (`build-logic/`), not in `allprojects` or `subprojects` blocks.
- Dependency and plugin versions live in a version catalog (`gradle/libs.versions.toml`).
- A Java toolchain pins Java 25, independent of the JDK that runs Gradle.
- The build cache and configuration cache are on (`gradle.properties`).
- Build scripts stay declarative: logic goes into convention plugins, where it can be reviewed and tested.

### Consequences

- Good, because incremental compilation and caching keep builds fast as modules are added.
- Good, because convention plugins and the version catalog keep module build files short and consistent.
- Good, because toolchains make the Java version explicit, and CI can test a second Java version with one setting.
- Bad, because build scripts are code. Without the rules above they drift into custom logic that is hard to review.
- Bad, because Gradle releases often and its upgrades can need build changes, so wrapper upgrades need attention.

### Confirmation

CI builds and tests with `./gradlew build`. `AGENTS.md` lists the exact commands. Reviews reject build logic outside `build-logic/`.

## Pros and Cons of the Options

### Maven

- Good, because the build is declarative, widely known and stable, and agents edit `pom.xml` reliably.
- Bad, because it has no build cache and is less incremental, so feedback slows as the project grows.
- Bad, because custom build logic needs plugins, which are harder to write.

## More Information

- Related: [ADR-0014](0014-build-the-java-code-with-maven.md), which proposed Maven and was rejected.
