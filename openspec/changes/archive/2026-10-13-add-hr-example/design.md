# Design

## Context

The HR domain is described in `docs/reference/hr-domain.md`. Applications use Cairn through `cairn-api`, `cairn-core`, `cairn-documents`, generated types (`cairn-codegen` and its Gradle plugin) and, for agents, `cairn-agent-tools`. Inside this repository the Gradle plugin cannot be applied to a sibling subproject, so the `cairn.reference-types` convention runs the generator directly; the example needs the same for its own document.

## Goals / Non-Goals

**Goals:**

- Test Cairn end to end on a realistic domain, through its public API only.
- Find what Cairn cannot express, and record it.

**Non-Goals:**

- Change Cairn's behavior, beyond fixing bugs the example finds.

## Decisions

### D1. The subproject

- `cairn-example-hr`, package `io.github.enricoag1982.cairn.example.hr`, with a `module-info.java` that requires only Cairn's exported modules. `cairn-agent-tools` has no module, so the tests that use it run on the class path, as its own tests do.
- `src/main/cairn/hr-model.cairn.yaml`: the schema document, origin `hr-model`, version `1.0.0`.
- Generated types in package `io.github.enricoag1982.cairn.example.hr.model`, into the main source set, by a new convention plugin `cairn.generated-types` in `build-logic/`: a `JavaExec` task on the runtime class path of `cairn-codegen`, with the documents of `src/main/cairn/` and the package from the extension `generatedTypes { packageName }` (a subproject's `gradle.properties` is not visible to `providers.gradleProperty`). It also adds `src/main/cairn` to the resources, so the application reads its schema document at run time. `cairn.reference-types` stays as it is: it generates into the test source set with the project's own generator, so moving it onto the new plugin would not be simpler.
- Main code: the four rules of the domain page, each a subclass of its generated rule base class, listed in `META-INF/services` for the service loader; and `Brightwater`, the stories' data as transactions built with generated builders and payloads.

### D2. Tests

- One test per story, with the display name `HR-n: <story>`.
- HR-1 reads the schema document and registers it; HR-1 and HR-2 write the company through payloads (HR-1) and generated builders (HR-2), so both paths run.
- HR-9 and HR-10 run queries built with typed references; HR-9 also as JSON.
- HR-12 purges with the principal `dpo` and the reason `DSR-2026-0042`.
- HR-13 runs the agent tools: `cairn_describe`, `cairn_json_schema`, `cairn_dry_run`, `cairn_commit` under a policy that requires approval for data commits, the approval through `ApprovalQueue`, then `cairn_query`.
- HR-14 generates a company of 40 employees with a year of monthly changes from seed 42, commits it to a durable store and to an in-memory store, reopens the durable store, and compares views and query results at every month end; it also checks that each month's headcount equals its active employments.

### D3. Gaps

When the example meets something Cairn cannot express, the code works around it in the most direct way, and the domain page's Gaps section names it, with what Cairn would need; where a test can show the gap, it does, with an assertion that flips when the gap is closed. The research note keeps its expected gaps and points to the page for those found. A bug in Cairn is fixed in this change, with a test in the module where it lives.

## Risks / Trade-offs

- [The example duplicates what the engine's tests cover] → It tests the platform as a whole and from outside; each story names what it exercises.
- [The generated data makes the build slower] → 40 employees and twelve months keep it to seconds.
