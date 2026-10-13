---
status: concluded
date: 2026-10-13
authors: [enricoag1982, Claude Code]
related: [ADR-0012, ADR-0017, ADR-0018]
---

# Research: making Cairn AI-native

## Question

Cairn must be AI-native: AI agents should be able to add functionality, types and entities, with strong validation and easy construction.
How does that change the design in the [API research note](2026-10-12-graph-system-of-record-api.md)?
In particular: in which format are types defined, and where does custom validation logic live, given that it should be written in Java?

This note records the review of 2026-10-13. The decisions moved into ADR-0017, a revised ADR-0012 and ADR-0018.

## Findings

### What agents need, and what the first design lacked

| Agents need | The first design | Now |
| --- | --- | --- |
| Definitions they can write and register without a compiler | Annotated Java interfaces, built and released; runtime types a follow-up | Schema documents registered at runtime (ADR-0012) |
| A published contract to build input from | A Java builder API | A JSON Schema for documents, and one per entity type for payloads (ADR-0012, ADR-0017) |
| Meaning, not only structure | Names and types only | Descriptions, examples, allowed values, patterns and lengths in the meta-model (ADR-0017) |
| A preview before a change | Registration applies or rejects | A plan of schema changes, and a dry run of transactions (ADR-0017) |
| Problems they can act on | A code, a path and a message | Also what was expected, what was found and how to fix it, with a catalog of codes (ADR-0017) |
| Safe retries and an audit of who did what | An idempotency key and a reason (ADR-0013) | Also who the principal acts for (ADR-0017) |
| A way in | A Java API in the application's JVM | Tools over the same API, starting with MCP (ADR-0017) |
| Limits | None | An approval policy: people approve breaking changes, migrations and purges (ADR-0017) |

The programmatic schema API built in `add-type-model` already fits: definitions are plain data that refer to each other by name, and every problem is reported at once.
Schema documents are read into the same definitions.

### Formats for defining types

| Format | Readable | Agents know it | Editor support | Fits Cairn's concepts | Parser to maintain |
| --- | --- | --- | --- | --- | --- |
| JSON | fair | very well | through JSON Schema | with our own vocabulary | no |
| YAML | good | very well | through JSON Schema | with our own vocabulary | no |
| LinkML | good | somewhat | through JSON Schema | partly: classes, mixins and slots; no relationship ends, origins or time | no |
| GraphQL SDL | good | well | plugins | partly: no multiplicity at both ends, composition or aliases | a library |
| Smithy, Pkl | good | little | plugins | through their extension mechanisms | a library |
| A language of our own | best | not at all | a plugin we write | fully | yes |
| Annotated Java interfaces | good, for Java developers | well | full | fully | an annotation processor |

YAML for people and JSON for tools, with Cairn's own vocabulary and a published JSON Schema, gives a readable format that agents know well, with editor support and no parser of our own.
A document for part of the [reference domain](../reference/reference-domain.md) could look like this, with the descriptions, examples, patterns, lengths and rules proposed here. The exact format is for the change that builds it:

```yaml
cairn: 1
origin: { name: reference-model, version: "1.0" }

traits:
  Named:
    description: Something with a name that people use for it.
    attributes:
      name: { type: string, required: true, maxLength: 200 }

entityTypes:
  Party:
    abstract: true
    description: A person or organization that can own others, hold accounts and play roles.
    includes: [Named]
  Organization:
    extends: Party
    attributes:
      lei:
        type: string
        unique: true
        pattern: "^[A-Z0-9]{18}[0-9]{2}$"
        description: Legal Entity Identifier (ISO 17442).
        examples: ["5493001KJTIIGC8Y1R12"]
    rules:
      - name: lei-checksum
        description: The LEI's check digits are valid (ISO 17442, MOD 97-10).

relationshipTypes:
  Owns:
    description: An organization holds a share of a party.
    from: Organization
    to: Party
    targetsPerSource: "0..*"
    sourcesPerTarget: "0..*"
    inverse: ownedBy
    attributes:
      share: { type: decimal, required: true, range: "(0, 100]" }
    rules:
      - name: total-ownership-at-most-100
        description: The owners of a party hold at most 100% of it together, at every point in effective time.
```

### Where custom logic lives

A schema document declares constraints; it does not hold code.
Logic that a document cannot declare is written in Java, as rules that the document names. Agents compose existing rules by name. New rules are Java code, which reaches production through code review, written by a person or a coding agent.
Kubernetes validates custom resources in the same layers: a declarative OpenAPI schema, then CEL expressions in the resource definition, then code in admission webhooks.
Expressions in CEL are a possible later level, for checks within one entity that agents can write at runtime.
ADR-0018 has the rule contract.

### Other prior art

SirixDB is an embeddable, append-only, bitemporal store written in Java. It versions JSON and XML documents rather than typed graphs, but is worth a look for its storage of revisions.

## Options and trade-offs

- **Agents as first-class users, or an agent layer added later.** A layer on top must translate Java types and exceptions for agents, and drifts from what it wraps. Building agents' needs into the core API costs scope now, but helps people as much as agents (ADR-0017).
- **Schema documents or annotated Java interfaces.** Interfaces give the best editor support for Java developers, but need a compiler for every new type. Documents work at runtime and in any language; generated Java types keep typed access (ADR-0012).
- **Java rules, expressions or scripts.** Java rules are reviewed, tested and typed, but need a release. Expressions need no release, but are limited. Scripts are unsafe inside the engine (ADR-0018).

## Conclusion

- Proposed: ADR-0017 (agents as first-class users), ADR-0012 revised (schema documents, generated Java types) and ADR-0018 (declarative constraints and named Java rules).
- ADR-0005 still holds: the engine, the generated types and the rules are Java.
- Assumptions, until the user says otherwise:
  - Agents use a running store through tools first. Coding agents that write Java work through pull requests as usual.
  - Agents may apply compatible schema changes directly; breaking ones need a person.
- Open:
  - The library for reading JSON and YAML (for example Jackson, or SnakeYAML Engine with a JSON library) is a new runtime dependency, to be agreed with `add-schema-documents`.
  - The MCP SDK for Java is a dependency of the agent adapter only.
  - CEL expressions need their own ADR.
- Next steps, as OpenSpec changes in this order. This replaces the list in the [API research note](2026-10-12-graph-system-of-record-api.md#conclusion):
  1. Accept or reject the proposed ADRs that `add-type-model` relies on, then archive it.
  2. `add-schema-documents`: the YAML and JSON format and its JSON Schema; descriptions, examples, allowed values, patterns and lengths; rule references checked against registered rules; planning a schema change; problems with expected and actual values and hints, and the catalog of codes. The reference model becomes a document.
  3. An ADR on entity IDs, then `add-entity-versions`: entities on two time axes, in memory; the generic API with JSON payloads and a JSON Schema per entity type; dry runs, idempotency keys, principal and reason; rules run at commit.
  4. `add-relationships`: relationships as data, integrity at both ends, navigation and traversal, and rules spanning several entities.
  5. `add-agent-tools`: the MCP adapter, the approval policy and the agent task suite.
  6. `add-java-type-generation`: the Gradle plugin that generates value types, builders, the typed query model and rule base types.
  7. `add-durable-commit-log`: a file-based commit log, snapshots and recovery.
  8. `add-queries-and-aggregation`.
  9. Later: CEL expressions, derived attributes, feature flags and storage capabilities, change feed, purge, a RocksDB store.

## Sources

- Model Context Protocol: <https://modelcontextprotocol.io/>
- JSON Schema: <https://json-schema.org/>
- YAML 1.2.2: <https://yaml.org/spec/1.2.2/>
- LinkML: <https://linkml.io/>
- Smithy: <https://smithy.io/>
- Pkl: <https://pkl-lang.org/>
- Common Expression Language: <https://cel.dev/>
- Kubernetes, validation rules for custom resources: <https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/#validation-rules>
- RFC 9457, problem details for HTTP APIs: <https://www.rfc-editor.org/rfc/rfc9457>
- SirixDB: <https://sirix.io/>
