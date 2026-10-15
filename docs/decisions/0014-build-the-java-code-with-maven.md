---
status: rejected
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Build the Java code with Maven

## Context and Problem Statement

[ADR-0005](0005-choose-primary-implementation-language.md) chose Java 25 and left the build tool to its own decision.
The first change that needs code, `add-type-model`, cannot start until the build exists.
The build will hold several modules (API, core engine, annotation processor, conformance suite, storage implementations) and must be easy for people and AI agents to change.

## Decision Drivers

- Predictable builds that agents can read and edit reliably
- Fast feedback from compile and test
- Multi-module projects and annotation processors well supported
- Mature tooling: editors, CI, publishing artifacts

## Considered Options

- Maven
- Gradle with the Kotlin DSL

## Decision Outcome

Proposed option: "Maven", because its declarative build is predictable and easy for agents to edit correctly, and it supports everything the planned modules need.

- A parent `pom.xml` at the repository root, with one module per building block (first `sysrec-api` and `sysrec-core`).
- The Maven Wrapper (`mvnw`) is committed, so contributors and CI use the same Maven version.
- `maven.compiler.release` is 25.
- Dependency and plugin versions are pinned in the parent.

### Consequences

- Good, because the build is declarative, widely known and stable.
- Good, because agents and editors handle `pom.xml` reliably.
- Bad, because builds have no build cache and are less incremental than Gradle's, so feedback slows as the project grows.
- Bad, because custom build logic is harder to write than in Gradle.

### Confirmation

CI builds and tests with `./mvnw verify`. `AGENTS.md` lists the exact commands.

## Pros and Cons of the Options

### Gradle with the Kotlin DSL

- Good, because of incremental builds and the build cache, which give faster feedback on large builds.
- Good, because custom build logic is plain code.
- Bad, because build scripts are code, so they vary more between projects and are harder to review and edit correctly.

## More Information

- Revisit if full builds become slow enough to hurt the feedback loop.
- Rejected on 2026-10-12 in favor of Gradle: [ADR-0015](0015-build-the-java-code-with-gradle.md).
