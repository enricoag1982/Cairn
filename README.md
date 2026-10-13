*A cairn is a pile of stones that walkers build up, one stone at a time, to mark the way. Cairn keeps records the same way: history only grows, and the links between records show the path.*

# Cairn

A system of record for enterprise data.
You, or your AI agents, define typed entities and relationships, with inheritance. An engine embedded in your JVM application stores them with full history on two time axes, commits consistent transactions, and answers traversals and aggregations.
The API hides the storage implementation: the same behavior in memory or in a key-value store on disk.

> [!NOTE]
> **Status:** early. The schema API (defining, validating, planning, registering and evolving types), schema documents, and an in-memory store for entities and relationships with history on two time axes, which runs Java rules at commit, are built; so are transaction payloads and JSON results, tools that serve a store to AI agents over the Model Context Protocol, Java types generated from schema documents with a Gradle plugin, and stores kept on disk that recover after a crash; queries are not. The core design is recorded in accepted decision records.

## Ideas in one page

- **Two time axes.** Every fact has an *effective time* (when it is true in the real world, past or future) and a *transaction time* (when Cairn recorded it). Any past answer can be reproduced, even after corrections. [ADR-0008](docs/decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)
- **A typed graph.** Entity types with single inheritance and traits; first-class, directed relationships with their own attributes and history. [ADR-0010](docs/decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md)
- **Whole-entity versions.** Each change records a new version of the entity for an effective period; nothing is overwritten. [ADR-0009](docs/decisions/0009-version-whole-entities-rather-than-single-attributes.md)
- **Embedded, single writer.** One writer orders all commits; any number of readers work on immutable snapshots. [ADR-0007](docs/decisions/0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)
- **Views and context.** Reads go through immutable views bound to a context: time coordinates, caller, limits and feature flags resolved by a gateway. [ADR-0013](docs/decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md)
- **Storage behind a narrow interface.** An ordered key-value store and a commit log; the in-memory implementation is the reference, and every implementation passes the same conformance suite. [ADR-0011](docs/decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md)
- **Types in schema documents.** YAML 1.2, which tools can also send as JSON, checked against a published JSON Schema and registered in a running store; Java types are generated from them. [ADR-0012](docs/decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)
- **Logic in Java.** Documents declare constraints; anything more is a Java rule that documents name and the engine runs at commit. [ADR-0018](docs/decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)
- **Built for AI agents.** Agents read the schema, preview changes, get problems they can act on, and work through tools over the same API; people approve what can lose data. [ADR-0017](docs/decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md)

The reasoning behind all of this is in the [API research note](docs/research/2026-10-12-graph-system-of-record-api.md) and the [AI-native research note](docs/research/2026-10-13-ai-native-schema-and-rules.md).

## Where to start

- [AGENTS.md](AGENTS.md): how this repository works, for humans and AI agents
- [Decision log](docs/decisions/README.md): what is decided and what is still open
- [Glossary](docs/glossary.md): the terms used in specs, code and conversation
- [Reference domain](docs/reference/reference-domain.md): the example model used in scenarios and tests
- [Architecture](docs/architecture/README.md): how it is built
- [Serve a store to AI agents](docs/guides/serve-a-store-to-ai-agents.md): run the agent server and connect Claude Code or another MCP client
- [Generate Java types](docs/guides/generate-java-types.md): typed access, builders and rules from your schema documents
- [Run a durable store](docs/guides/run-a-durable-store.md): keep a store on disk, snapshots, crashes and limits
- [Setup status](docs/guides/start-a-new-project-from-this-template.md#status-in-cairn): the template setup steps that are still open

## How this repository works

It was created from the [openSpecProjectStructure](https://github.com/enricoag1982/openSpecProjectStructure) template and keeps its rules:

| Knowledge | Home |
| --- | --- |
| What the system must do | `openspec/specs/`, changed only through OpenSpec changes in `openspec/changes/` |
| Why it is built this way | `docs/decisions/` (MADR ADRs, append-only) |
| How it is built now | `docs/architecture/`, `docs/guides/`, `docs/reference/` |
| Vocabulary and principles | `docs/glossary.md`, `docs/principles.md` |
| Explorations | `docs/research/` (dated, non-normative) |

Requirements: Node.js 20.19 or later for the doc and spec tooling, and a JDK 25 for the Java build (Gradle, [ADR-0015](docs/decisions/0015-build-the-java-code-with-gradle.md)).

```bash
npm ci          # install the doc and spec tooling (pinned versions)
npm run check   # validate specs, lint Markdown, check decisions, links and review dates
./gradlew build # compile, test and build Javadoc for the Java modules
```

The workflow, from idea to merged change, is in [development workflow](docs/process/development-workflow.md). With Claude Code: `/opsx:explore`, `/opsx:propose`, `/opsx:apply`, `/opsx:archive`.
