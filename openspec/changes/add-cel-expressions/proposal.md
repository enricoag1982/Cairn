# Proposal

## Why

ADR-0018 left a third level of validation for later: checks within one entity, written in CEL in the schema document, which people and agents add at runtime without deploying code. The HR example needs one: `leave-dates-in-order` is a Java rule only to compare two attributes of one version. ADR-0030 decides how checks work and that an optional module runs them.

## What Changes

- Entity types and relationship types may declare *checks*: a name, a CEL expression over `self`, the version's values, and an optional message. They apply to subtypes too.
- The schema registry compiles checks at registration and rejects invalid ones with problems located at the check, in the expression and in the document. Without an expression module it rejects definitions that declare checks.
- Commits run the checks on every segment a transaction writes, with the declarative constraints, and reject a false result (`CHECK_VIOLATED`) or an evaluation error (`CHECK_FAILED`).
- Adding a check to an existing type, or changing its expression, is a breaking change; removing one, or changing only its message, is compatible.
- Schema documents and their JSON Schema gain `checks`.
- A new module `cairn-cel` implements the expression interface with `dev.cel:cel`, found through `ServiceLoader`; the agent server includes it.
- The HR example replaces the Java rule `leave-dates-in-order` with a check.

### Non-goals

- Checks across versions, entities or relationships' ends; functions of Cairn's own in expressions.
- Derived attributes.
- Exact decimal arithmetic in checks.
- Checks on traits.

## Capabilities

### New Capabilities

- `checks`: checks in CEL, declared on types, compiled at registration and run at commit.

### Modified Capabilities

- `schema-documents`: the `checks` of a type definition.
- `schema-evolution`: adding, changing and removing checks.

## Impact

- **Decisions:** ADR-0030, accepted under the standing approval with this proposal; it relies on ADR-0017, ADR-0018 and ADR-0025, and adds `dev.cel:cel` to the version catalog, used only by `cairn-cel`.
- **Code:** `cairn-api` (the `Check` definition, the expression interface, problem codes); `cairn-core` (compiling checks at registration, running them at commit, the schema's checks per type, evolution rules); `cairn-documents` (reading, writing and the JSON Schema); a new subproject `cairn-cel`; `cairn-agent-tools` (the server's runtime path); `cairn-example-hr` (the check replaces a rule).
- **Docs:** a guide to writing checks, the glossary (Check), the architecture page, the HR domain page, the reference domain page (test-only checks), AGENTS.md, README.md and `openspec/config.yaml`.
