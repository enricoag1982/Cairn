---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Design Cairn so that AI agents are first-class users

## Context and Problem Statement

Cairn must be AI-native: AI agents should be able to add functionality, types and entities, with strong validation and easy construction.
The design so far (ADR-0007 to ADR-0013) assumed Java developers as the only authors of types, and people or applications as the only writers of data.
We need to decide how far agents shape the design: whether they are an add-on over a Java API, or users the core API is built for.
See the [AI-native research note](../research/2026-10-13-ai-native-schema-and-rules.md).

## Decision Drivers

- Agents can find out what exists (types, attributes, relationships, rules, options) without reading source code
- Agents can build valid input from a published contract, check it, and see the effect before applying it
- Every rejection explains what was expected and how to fix it, in a form a program can act on
- People stay in control of changes that can lose or reinterpret data
- One API for people, applications and agents, so that behavior is the same for everyone
- The embedded engine of [ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md) stays small: tools for agents are adapters, not part of the core

## Considered Options

- Agents as first-class users of the core API
- A separate agent layer added later over the Java API
- Agents only write code, through pull requests

## Decision Outcome

Chosen option: "Agents as first-class users of the core API", because the properties agents need (machine-readable contracts, validation with explanations, previews) also make the API better for people, and are much cheaper to build in from the start than to add later.

### Candidate principle

**Agents are first-class users: everything they need is machine-readable, validated and previewable.**
It joins the candidate principles in the [API research note](../research/2026-10-12-graph-system-of-record-api.md#conclusion), to be agreed with them before they enter [the principles](../principles.md).
It does not conflict with the working principle "Agents are contributors, not authorities", which is about contributing to this repository. Agents that use a running store are limited by the approval policy below.

### Commitments

1. **Definitions are data with a published format.** Types are defined in schema documents, written in YAML 1.2, which includes JSON, and described by a published JSON Schema ([ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)). Types registered at runtime are the normal case, not a follow-up.
2. **The model describes itself.** Types, attributes, relationship types and rules carry descriptions and examples. Allowed values, patterns and lengths are declared, not hidden in code. An introspection API returns the whole schema, the available rules ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)), and the options and storage capabilities a store supports.
3. **Every change can be previewed.** Planning a schema change returns the classified differences and the problems without applying anything. Transactions on entities have a dry run that validates without committing.
4. **Problems explain themselves.** Every problem has a stable code, the path of the element concerned and a message. Where it applies, it also says what was expected, what was found and how to fix it. Every code Cairn defines is listed in a catalog with an explanation; codes that rules define are listed with their rule ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)).
5. **Entities can be written as JSON.** Besides typed Java access, the generic API accepts entities as JSON payloads, checked against a JSON Schema that the engine generates for each entity type.
6. **Writes say who and why.** The context of [ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md) already carries the principal, a reason and an idempotency key. It also says who the principal acts for when that is someone else, and the transaction records all of them, so that the history shows which changes agents made and for whom.
7. **Agents connect through tools.** An adapter publishes the API as tools for agents, starting with the Model Context Protocol (MCP): introspect, plan and register a schema change, validate and write entities, and query. Like a server, it is an adapter over the same plain values ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)), outside the core engine.
8. **People approve what can lose data.** A store has an approval policy. By default an agent may apply compatible schema changes and write entities, while purges need a person's approval. Breaking schema changes are rejected for everyone, as the `schema-evolution` spec says; once migrations exist, a breaking change with its migration needs a person's approval too. Java code, including rules, reaches production only through code review.

### Consequences

- Good, because agents can build types and entities from published contracts and correct their own mistakes from structured problems, without a person in the loop for safe changes.
- Good, because previews, explanations and self-description help people as much as agents.
- Good, because the engine keeps one behavior for every caller: tools for agents call the same API.
- Bad, because the scope grows: schema documents, JSON payloads, a problem catalog, previews and an agent adapter all need specs, tests and upkeep.
- Bad, because the public contract grows. The document format, the problem codes and the tool definitions must stay compatible once published.
- Bad, because an approval policy brings states that wait for a person, which the API and the tools must expose.

### Confirmation

- Every problem code Cairn defines is in the catalog, with an explanation and a test that produces it.
- The scenarios of the reference domain run through the generic API with JSON payloads as well as through typed Java access.
- Before the agent adapter ships, a set of agent tasks (for example "add an optional attribute to Person" or "record that one organization owns another") runs against it, and the tasks succeed without help from a person.

## Pros and Cons of the Options

### A separate agent layer added later over the Java API

- Good, because the first versions stay smaller.
- Bad, because a layer added on top must translate Java types, exceptions and builders into something agents can use, and that translation drifts from the API it wraps.
- Bad, because types defined in Java need a compiler, so agents could not add types to a running store.

### Agents only write code, through pull requests

- Good, because every change is reviewed by a person and built by CI.
- Good, because it needs nothing beyond what coding agents already do.
- Bad, because adding a type or fixing data needs a build and a release, which is too slow for the work agents are meant to do.
- Bad, because nothing helps agents that use a running store: they get no previews and no structured problems.

## More Information

- This ADR revised [ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md) while both were proposed: types are now defined in schema documents instead of annotated Java interfaces.
- Custom validation logic stays in Java: [ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md).
- Model Context Protocol: <https://modelcontextprotocol.io/>
- RFC 9457, problem details for HTTP APIs, inspires the problem fields: <https://www.rfc-editor.org/rfc/rfc9457>
- Agents read and write through the clearance that the agent server's operator gives them, empty by default, as [ADR-0036](0036-classify-attributes-and-read-and-write-them-through-a-clearance-that-withholds-the-rest.md) decides.
