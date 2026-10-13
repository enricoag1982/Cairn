# Proposal

## Why

Cairn is AI-native (ADR-0017): agents are meant to read a store's schema, plan and register schema changes, and write and read data, through tools over the same API as everyone else.
Everything they need now exists as Java API and as text formats: schema documents, transaction payloads, JSON results and generated JSON Schemas. What is missing is the way in, and the limits.
This change adds an adapter that publishes the API as tools over the Model Context Protocol (MCP), an approval policy that keeps people in control of what an agent may do, and a record of who made each schema change.

## What Changes

- A new adapter module that serves a store as MCP tools:
  - describe: the stored schema as schema documents, the available rules, and the approval policy; the JSON Schemas of documents, payloads and type values; and the explanations of problem codes
  - plan and register a schema document
  - dry-run and commit a transaction payload
  - read an entity or relationship, list the entities of a type, read a timeline, navigate, and traverse without a condition
- An approval policy per store and adapter: for each kind of operation (schema registration, data writes), allow it, require a person's approval, or deny it. Requests that need approval wait in a queue that people inspect and approve or reject through the Java API, and that agents can see.
- The adapter records its configured principal on every transaction it submits; a payload that names another principal is rejected.
- Schema transactions record who and why, as data transactions do.
- A runnable server for a store kept in memory, started from schema documents, speaking MCP over standard input and output.
- An agent task suite: scripted tasks that use only the tools and what they return, such as adding an optional attribute to Person or recording that an organization owns another, and that must succeed without help.

### Non-goals

- Running a language model in tests. The task suite scripts an agent's steps.
- Transports other than standard input and output, authentication and multi-tenant servers.
- Migrations and purges, which the approval policy will cover when they exist.
- Queries and aggregation as tools: `add-queries-and-aggregation`.

## Capabilities

### New Capabilities

- `agent-tools`: the MCP tools over a store, the approval policy and its queue, and the adapter's principal

### Modified Capabilities

- `schema-evolution`: schema transactions record their principal, the party acted for and a reason
- `schema-documents`: a problem about an element that a document no longer has is located at the closest enclosing element

## Impact

- **Decisions:** ADR-0017 (commitments 6, 7 and 8: who and why, tools, approval) and ADR-0007 (adapters outside the embedded engine). Both are accepted.
- **Code:** a new subproject, `cairn-agent-tools`, on the class path; `cairn-api` and `cairn-core` gain who and why for schema registrations; `cairn-documents` locates problems about removed elements.
- **Dependencies:** the MCP Java SDK 2.0.1 (`io.modelcontextprotocol.sdk:mcp-core` with its Jackson 3 JSON module) in `cairn-agent-tools` only, which brings Reactor, Jackson and SLF4J into that module. Approved by the user with the AI-native roadmap.
- **Docs:** a guide for running the server and connecting an agent, the glossary (approval policy, approval request), the architecture page.
