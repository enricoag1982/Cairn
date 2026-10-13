# Proposal

## Why

Cairn's capabilities are tested one at a time, mostly on the reference domain, which the engine's own tests were designed around. The user asked for an end-to-end test on a realistic domain: a basic HR system with employees, locations, organizations and approval processes, and data to test it all.
The research note `docs/research/2026-10-13-hr-system-as-a-test-domain.md` concluded that an HR system fits Cairn's model, and what it should exercise. This change builds it as an application would, through Cairn's public API, and records what Cairn could not express.

## What Changes

- The HR domain, described in `docs/reference/hr-domain.md`: the model, four Java rules, the people of the fictional company Brightwater, and stories HR-1 to HR-14 with expected outcomes.
- A new subproject, `cairn-example-hr`, which depends only on Cairn's public modules:
  - the schema document `hr-model.cairn.yaml`, and Java types generated from it;
  - the four rules, written against the generated rule base classes and found through the service loader;
  - a generator of test data: a company of 40 employees with a year of history, from a fixed seed;
  - one test per story, through schema documents, payloads, generated builders, queries, purges, a durable store and the agent tools; the tests also use the `cairn-api` test fixture `TestClock`;
  - a test that keeps the page's tables in step with the schema document, the rules and the data.
- A convention plugin in `build-logic/` that generates a project's types from its own schema documents with the project's generator, so that the example uses generated types as an application would with the Gradle plugin.
- The gaps the example finds, recorded on the domain page and in the research note, as candidates for later changes.

### Non-goals

- Changing Cairn's behavior. A gap that the example finds becomes a later change, unless it is a bug, which this change fixes.
- An HR product: no user interface, payroll, or time tracking.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. The change adds an example and its tests; `.openspec.yaml` sets `skip_specs: true`.

## Impact

- **Decisions:** none new. The example relies on the accepted ADRs it exercises: ADR-0008, ADR-0010, ADR-0012, ADR-0015 (Gradle, for the subproject and the convention plugin), ADR-0017, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022, ADR-0023 and ADR-0024. The user chose this work on 2026-10-13, which covers the new subproject.
- **Code:** a new subproject `cairn-example-hr`; a convention plugin; no new dependencies.
- **Docs:** `docs/reference/hr-domain.md`, the research note, the architecture page's testing section, `AGENTS.md` and `README.md`.
