---
status: proposed
date: 2026-09-30
decision-makers: [project maintainers]
consulted: []
informed: []
---

# Choose the primary implementation language

## Context and Problem Statement

The repository starts with specs, decisions and documentation, and no code.
Java is the leading candidate, but the first capabilities have not been specified yet, so the choice is **deliberately deferred**.
Choosing the language before knowing the workload (latency, integrations, deployment target, team skills) risks a decision that is costly to reverse.

This ADR stays `proposed` until it is decided. While it is open, contributors and agents must not add build files, source trees or language-specific tooling.

## Decision Drivers

- Fit for the first specified capabilities (to be filled in once `openspec/specs/` has content)
- Team skills and hiring
- Ecosystem maturity for the expected integrations
- Long-term maintainability and upgrade path (LTS releases)
- Quality of AI coding agent support (training coverage, tooling, fast feedback from compiler and tests)
- Build and test speed, so agents get feedback quickly

## Considered Options

- Java (current LTS) with Gradle or Maven
- Kotlin on the JVM
- TypeScript on Node.js
- Go
- *(add options as they come up)*

## Decision Outcome

Not decided yet. **Revisit when** the first OpenSpec change that needs code is proposed, or by the date the team sets for it.

When deciding: set `status: accepted`, fill in the outcome, then in the same PR:

- add build files and source layout
- replace the placeholder commands in `AGENTS.md` ("Commands")
- add language entries to `.gitignore` and `.editorconfig`
- add a CI job for build and test
- update `docs/architecture/README.md` (constraints, solution strategy)

### Consequences

- Good, because the structure, workflow and documentation can be used right away without committing to a stack.
- Bad, because agents have no build or test commands to run until this is decided.

## Pros and Cons of the Options

### Java (current LTS) with Gradle or Maven

- Good, because of the mature ecosystem, strong static typing and long LTS support windows.
- Good, because compiler and test feedback make agent-written code easier to verify.
- Neutral, because of the choice between Gradle and Maven, which needs its own ADR.
- Bad, because it is more verbose than the alternatives, which costs agent context and review effort.

### Kotlin on the JVM

- Good, because it runs on the same ecosystem as Java with less boilerplate.
- Bad, because builds are slower and the hiring pool is smaller than Java's.

### TypeScript on Node.js

- Good, because the documentation tooling already runs on Node.js, so the stack would be shared.
- Bad, because type safety is weaker at runtime.

### Go

- Good, because of simple deployment and fast builds.
- Bad, because the domain-modelling features are more limited.

## More Information

Open question tracked here instead of in an issue, so that agents see it in the decision log.
