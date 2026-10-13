# Design

## Context

Agents use a store through tools (ADR-0017, commitment 7) that are adapters over the plain API (ADR-0007). The formats exist: schema documents (`schema-documents`), transaction payloads and JSON results with generated JSON Schemas (`json-payloads`). This change adds the MCP adapter, the approval policy (commitment 8) and who and why for schema changes (commitment 6). The requirements are in the deltas `agent-tools`, `schema-evolution`, `schema-documents` and `entity-rules`.

## Goals / Non-Goals

**Goals:**

- Tool handlers that are plain Java and testable without MCP, wrapped by a thin MCP layer.
- An approval policy and queue that people drive through Java, and agents can observe.
- A server that runs from the command line for demos and for connecting agents such as Claude Code.

**Non-Goals:**

- Other transports, authentication, multi-tenancy.
- A language model in tests.

## Decisions

### D1. A new subproject, `cairn-agent-tools`

- Package `io.github.enricoag1982.cairn.agents`. It depends on `cairn-core`, `cairn-documents` and the MCP Java SDK 2.0.1 (`mcp-core` and `mcp-json-jackson3`), which only this subproject carries.
- It has no `module-info.java` and runs on the class path. The SDK's jars declare automatic module names that are not valid Java names (`io.modelcontextprotocol.sdk.mcp-core`), so the module system cannot resolve them, and a module that reads the class path would need build logic to split the two paths. Its internals are package-private instead. The design of `add-type-model` foresaw this: drop `module-info.java` where it costs more than it protects. On the class path, the packages that `cairn-core` does not export are not enforced for this subproject; it uses only the exported ones.
- The subproject's build file applies the `application` plugin with the server's main class; that is declarative and needs no build logic.
- SLF4J has no binding in the server, so nothing is logged to standard output, which carries the protocol.

### D2. Tool handlers and the MCP layer

- `AgentTools` holds one method per tool, taking and returning JSON text. It is built from a `Store`, an `AgentPolicy` and an `ApprovalQueue`.
- `McpAdapter` registers every handler with the SDK's synchronous server: name, description, input JSON Schema, and a call that returns the handler's JSON as text content. A handler never throws for bad input; it returns problems, and the call result is not marked as an error. The SDK's own validation of tool inputs is off, so that the handlers report problems with arguments themselves: the path is `@arguments.<name>`, or `@arguments` for arguments that are not a JSON object, and the codes are those of payloads (`MISSING_KEY`, `INVALID_VALUE`, `UNKNOWN_KEY` with the closest name as a hint, `DOCUMENT_SYNTAX`). A call with argument problems returns `{ problems }`, except the tools with an outcome, which return their own form with `outcome: "rejected"` (the plan with `changes: []`). An unknown problem code or approval request ID is `INVALID_VALUE`. The explanations of the codes that tools reuse say how tools use them.
- Tool inputs (JSON objects): `cairn_describe {}`; `cairn_json_schema { kind: "document" | "payload" | "values", type? }`; `cairn_explain_problem { code }`; `cairn_plan_schema { document }`; `cairn_register_schema { document, reason? }`; `cairn_dry_run { payload }`; `cairn_commit { payload }`; `cairn_read_entity { id, effectiveAt, knownAt? }`; `cairn_read_relationship { id, effectiveAt, knownAt? }`; `cairn_list_entities { type, effectiveAt, knownAt? }`; `cairn_timeline { id, knownAt? }`; `cairn_navigate { id, name, effectiveAt, knownAt? }`; `cairn_traverse { id, name, maxDepth, effectiveAt, knownAt? }`; `cairn_approval_status { requestId }`. `knownAt` is `{ basis: n }` or `{ transactionTime: "…" }`.
- `document` and `payload` inputs are strings holding the text, so that problems keep lines and columns. Payloads are read with `IdLookup.from` the store's latest view, for dry runs, commits and approved requests alike; `cairn_timeline` and the reads take an ID and use the lookup to tell entities from relationships.
- Outputs reuse the JSON forms of `json-payloads` where they exist; the adapter defines the others with Jackson (which the MCP SDK brings): a plan `{ outcome: "wouldApply" | "unchanged" | "rejected", changes: [{ path, description, compatible, hint }], problems }`, a registration `{ outcome: "applied" | "unchanged" | "rejected" | "pending", basis?, transactionTime?, problems?, request? }`, a dry run `{ outcome: "wouldCommit" | "rejected", problems? }`, a commit `{ outcome: "committed" | "rejected" | "pending", receipt?, problems?, request? }`, rule descriptors `{ name, version, description, appliesTo, codes, hasCode }`, explanations `{ code, explanation }`, and approval requests (D4). Reads wrap the `json-payloads` form as `{ effectiveAt, basis, result }`; `cairn_timeline` has no effective time and returns `{ basis, kind: "entity" | "relationship", result }`. A read by an ID the store does not know returns `UNKNOWN_ENTITY` or `UNKNOWN_RELATIONSHIP`, by a type or relationship name the schema does not have `UNKNOWN_ENTITY_TYPE` or `UNKNOWN_RELATIONSHIP_TYPE`; one that is not effective at that time has the result `null`.
- Problems about a schema document are located with `SchemaDocument.locate`. A problem about an element that the new document no longer has, such as a removed attribute, is located at the closest enclosing element it has: this change modifies the `schema-documents` requirement on locating problems, which put such problems at the root.
- `cairn_json_schema` for an unknown type name returns an `UNKNOWN_ENTITY_TYPE` problem instead of the exception `JsonSchemas.values` throws.

### D3. Describing the store

- `cairn_describe` writes each origin's stored definition as a YAML schema document (`SchemaDocuments.writeYaml`), lists the available rules with `hasCode` and their codes qualified by the rule's name as in problems (`lei-checksum/invalid-check-digits`), the problem codes with their explanations, the latest basis, the adapter's principal and party acted for, and the policy (`allow`, `requireApproval` or `deny` for each kind of operation).
- `Store` gains `declaredRules()`, the descriptors of the rules the store declares without code, so the adapter can tell which rules have code.

### D4. Approval policy and queue

- The policy belongs to the adapter: each `AgentTools` has one, so two adapters on one store can differ. Agents reach a store only through an adapter, so this is how ADR-0017's policy of a store is applied.
- `AgentPolicy(Decision schemaRegistrations, Decision dataCommits)` with `Decision` being `ALLOW`, `REQUIRE_APPROVAL` or `DENY`; `AgentPolicy.defaults()` allows both.
- `ApprovalQueue` keeps `ApprovalRequest(String id, Kind kind, String principal, Optional<String> onBehalfOf, Optional<String> reason, Instant submittedAt, String content, Status status, Optional<String> outcome, Optional<String> decidedBy, Optional<Instant> decidedAt)` in memory; IDs are UUIDv7. The reason is the one given with a schema registration, or the payload's; the party acted for is the one the transaction will record, the payload's or else the adapter's. People call `pending()`, `all()`, `find(id)`, `approve(id, approver)` and `reject(id, approver, reason)`. Approving runs the request as submitted by the agent's principal, against the state at that moment, and keeps the result as JSON in `outcome`; a request that is now invalid is approved but its outcome holds the problems. Rejecting keeps `{ outcome: "declined", reason }`.
- A request is checked before it is kept: a registration whose plan is rejected, or that changes nothing, and a commit whose dry run is rejected, are answered at once, so that people only see requests that would apply.
- New problem codes: `APPROVAL_DENIED` (the policy denies the operation) and `PRINCIPAL_MISMATCH` (a payload names another principal).

### D5. Principals

- `AgentTools` is created with the principal it acts as and an optional party acted for. A payload must name the adapter's principal: another `principal` is `PRINCIPAL_MISMATCH` with the path `@transaction.principal`, which `json-payloads` locates at the payload's `principal` key; a payload without `onBehalfOf` gets the configured one.
- Schema registrations through the tools record the adapter's principal (D6).

### D6. Who and why for schema changes

- `SchemaRegistry` gains `register(Origin, SchemaDefinition, Submission)` with `Submission(String principal, Optional<String> onBehalfOf, Optional<String> reason)`. The existing `register(Origin, SchemaDefinition)` records the principal `@unknown`.
- `SchemaTransaction` gains `principal`, `onBehalfOf` and `reason`.

### D7. The server

- `CairnAgentServer.main` takes `--schema <file>` (repeatable), `--principal <name>` (required), `--on-behalf-of <name>` and `--policy schema=<allow|approve|deny>,data=<…>`. It creates an in-memory store, registers the documents as its principal with a reason naming the file, and serves the tools over standard input and output until the input closes.
- Rules: the store runs the rules it finds on the class path through the service loader (`StoreSettings.Builder.discoverRules()`), and declares without code every other rule that a document names, with a descriptor built from the documents: no codes, and the closest common supertype of the types that name it, or the first of them if they have none. Only documents given at start get this; a document registered later through `cairn_register_schema` that names an unknown rule is rejected. The start message on standard error lists the rules declared this way. The documents register, and writes to types that name a declared rule are rejected with `RULE_UNAVAILABLE`.
- Standard output carries only the protocol. The SDK's transport stops answering when the input closes, so a client keeps the input open until it has its answers.
- A guide shows how to connect it to an MCP client such as Claude Code.

### D8. The agent task suite

- `AgentTaskSuiteTest` runs scripted tasks through `AgentTools` only, using nothing but what the tools return: read the schema, edit the document as text, plan, register; build a payload from the generated JSON Schema, dry-run, read the problems and hints, fix, commit; read back. One test also goes through the MCP layer with the SDK's client to list the tools and call one, over an in-process transport that the tests define, since the SDK has none.

## Risks / Trade-offs

- [The MCP SDK brings Reactor and Jackson] → Only in this module; the engine stays free of them.
- [Approved requests run later than they were submitted] → They are validated again at approval; the outcome says what happened.
- [The server declares the unknown rules that its start documents name, so a misspelled rule name registers instead of being rejected, as ADR-0018 has registration do] → The start message lists every declared rule, and writes to the types that name one are rejected (`RULE_UNAVAILABLE`), as ADR-0018 has for rules without code.
- [Agents can read everything a store holds] → Access control is not designed yet; the server is for trusted local use, which the guide says.
