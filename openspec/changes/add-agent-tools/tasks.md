# Tasks

## 1. Build and API

- [x] 1.1 Add the MCP Java SDK 2.0.1 to the version catalog and the subproject `cairn-agent-tools` with the `application` plugin, on the class path (D1)
- [x] 1.2 Add `Submission`, `SchemaRegistry.register(Origin, SchemaDefinition, Submission)` and who and why on `SchemaTransaction`; implement in `cairn-core` (D6)
- [x] 1.3 Add `Store.declaredRules()` and the problem codes `APPROVAL_DENIED` and `PRINCIPAL_MISMATCH` (D3, D4)

## 2. Adapter

- [x] 2.1 `AgentTools` with every tool handler and its input JSON Schema (D2, D3)
- [x] 2.2 `AgentPolicy`, `ApprovalQueue` and `ApprovalRequest` (D4)
- [x] 2.3 The adapter's principal and party acted for (D5)
- [x] 2.4 `McpAdapter` and the server `CairnAgentServer` (D2, D7)
- [x] 2.5 Locate problems about removed elements at their enclosing element in `SchemaDocument.locate` (D2)

## 3. Tests

- [x] 3.1 One test per scenario of `agent-tools`, of the new `schema-evolution` requirement and of the new `schema-documents` and `entity-rules` scenarios, named `Scenario: <name>`
- [x] 3.2 The agent task suite (D8); verify `./gradlew build` passes

## 4. Documentation

- [x] 4.1 Add `Adapter`, `Agent tool`, `Approval request` and `Model Context Protocol (MCP)` to `docs/glossary.md`, and update `Approval policy`, `Problem` and `Schema transaction`
- [x] 4.2 Write a guide in `docs/guides/` for running the server and connecting an agent
- [x] 4.3 Update `docs/architecture/README.md`, `AGENTS.md` and `README.md`, and bump `last-reviewed`
- [x] 4.4 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
