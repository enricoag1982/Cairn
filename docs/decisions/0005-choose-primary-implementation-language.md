---
status: accepted
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Choose the primary implementation language

## Context and Problem Statement

The repository started with specs, decisions and documentation, and no code. The language choice was deliberately deferred until the workload was clearer.
The [API research note](../research/2026-10-12-graph-system-of-record-api.md) has since shaped sysRec as an engine that runs embedded in the application's JVM ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)), keeps data on the heap and on disk, and exposes typed Java access to user-defined types ([ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)).

## Decision Drivers

- Fit for an embedded data engine: control over heap and off-heap memory, garbage-collection options, and cheap blocking calls
- Team skills and hiring
- Ecosystem maturity for the expected integrations (RocksDB, FoundationDB, Kafka)
- Long-term maintainability and upgrade path (LTS releases)
- Quality of AI coding agent support (training coverage, tooling, fast feedback from compiler and tests)
- Build and test speed, so agents get feedback quickly

## Considered Options

- Java (current LTS) with Gradle or Maven
- Kotlin on the JVM
- TypeScript on Node.js
- Go

## Decision Outcome

Chosen option: "Java (current LTS)", which is **Java 25**, because it fits an embedded engine on the JVM best:

- On-heap and off-heap memory (the Foreign Function and Memory API), and a choice of garbage collectors
- Virtual threads, which make the synchronous API of ADR-0007 cheap
- Mature bindings for RocksDB and FoundationDB
- A clear path to value classes (Project Valhalla) for dense data on the heap

The build tool (Gradle or Maven) is a separate decision, still open.
Until it is made, the remaining steps of accepting this ADR wait: build files and source layout, the commands in `AGENTS.md`, language entries in `.gitignore` and `.editorconfig`, and a CI job for build and test.

Guidance that follows from this decision:

- Turn on compact object headers (`-XX:+UseCompactObjectHeaders`, the default from Java 27).
- Keep preview and incubating APIs (value classes, the Vector API, structured concurrency) out of the engine core. They may be used behind a feature flag in an experimental module.
- Make small API types immutable records that never rely on identity, so they can become value classes later.
- Build on Java 25, also run CI on the newest Java release, and plan the move to the next LTS release, Java 29 (September 2027).

### Consequences

- Good, because the language matches the engine's runtime and the users' applications, with no foreign-function boundary.
- Good, because compiler and test feedback make agent-written code easier to verify.
- Bad, because the engine inherits the JVM's memory and garbage-collection limits, which we must design around.
- Bad, because Java is more verbose than the alternatives, which costs agent context and review effort.

## Pros and Cons of the Options

### Java (current LTS) with Gradle or Maven

- Good, because of the mature ecosystem, strong static typing and long LTS support windows.
- Good, because compiler and test feedback make agent-written code easier to verify.
- Neutral, because of the choice between Gradle and Maven, which needs its own ADR.
- Bad, because it is more verbose than the alternatives, which costs agent context and review effort.

### Kotlin on the JVM

- Good, because it runs on the same ecosystem as Java with less boilerplate.
- Bad, because builds are slower and the hiring pool is smaller than Java's, and it adds little for an engine.

### TypeScript on Node.js

- Good, because the documentation tooling already runs on Node.js, so the stack would be shared.
- Bad, because type safety is weaker at runtime, and memory control is too limited for a data engine.

### Go

- Good, because of simple deployment and fast builds.
- Bad, because the domain-modelling features are more limited, and it does not run inside the users' JVM applications.

## More Information

- Release status of later Java versions, and why they matter to sysRec, is summarized in the research note.
