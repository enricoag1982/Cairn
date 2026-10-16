---
last-reviewed: 2026-10-16
owner: ""
---

# Serve a store to AI agents

How to run the Cairn agent server and connect an AI agent to it, such as Claude Code.
The server creates an in-memory store, registers the schema documents you give it, and serves the store as *agent tools* over the *Model Context Protocol* (MCP), on standard input and output. It includes `cairn-cel`, so documents may declare *checks* and *derived attributes* ([Write checks](write-checks.md), [Derive attributes](derive-attributes.md)).
What the tools do is specified in the `agent-tools` spec; why agents are first-class users is in [ADR-0017](../decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md).

> [!WARNING]
> The server is for trusted, local use. It has no authentication, any agent connected to it can read everything that the server's clearance covers, and the store is gone when the server stops. An agent may register schema changes unless `--policy schema=approve` or `deny` says otherwise; it cannot change classifications that its clearance does not cover, nor add a check or derived attribute over them on any type, abstract or not, but other changes still deserve a person's look.

## Build the server

You need JDK 25 for the build and to run the server: the start script uses `JAVA_HOME`, or `java` on the `PATH` if `JAVA_HOME` is not set.

```sh
./gradlew :cairn-agent-tools:installDist
```

This writes the start script `cairn-agent-tools/build/install/cairn-agent-tools/bin/cairn-agent-tools`, with the jars it needs in `lib/` next to it.
Run it with `--help` to see the options.

## Start it

```sh
cairn-agent-tools/build/install/cairn-agent-tools/bin/cairn-agent-tools \
    --principal agent:demo \
    --schema docs/reference/reference-model.cairn.yaml
```

| Option | Meaning |
| --- | --- |
| `--principal <name>` | Required. Who the agent acts as. The server records it on every transaction, schema or data, and rejects a payload that names another principal. |
| `--schema <file>` | A schema document to register at start, as the principal, with a reason that names the file. Repeat it for several origins. |
| `--on-behalf-of <name>` | The party the principal acts for, recorded on every schema registration and on every transaction whose payload names none. |
| `--policy schema=<p>,data=<p>` | The *approval policy* for schema registrations and data commits, each `allow` (the default), `approve` or `deny`. |
| `--clearance <c>[,<c>...]` | The *clearance* the agent reads and writes through: the *classifications* whose values it may see and change. Empty by default, so the agent sees no classified value. |

The server answers on standard output and writes nothing else there; its messages for you go to standard error.
It stops when its input closes. A client must keep the input open until it has its answers.

## Connect Claude Code

Add the server to Claude Code with absolute paths, so that it starts from any directory:

```sh
claude mcp add cairn -- /path/to/sysRec/cairn-agent-tools/build/install/cairn-agent-tools/bin/cairn-agent-tools \
    --principal agent:claude --schema /path/to/sysRec/docs/reference/reference-model.cairn.yaml
```

Or, to share it with a project, put it in the project's `.mcp.json`, with `JAVA_HOME` pointing at a JDK 25 if the default Java is older:

```json
{
  "mcpServers": {
    "cairn": {
      "command": "/path/to/sysRec/cairn-agent-tools/build/install/cairn-agent-tools/bin/cairn-agent-tools",
      "args": ["--principal", "agent:claude", "--schema", "/path/to/sysRec/docs/reference/reference-model.cairn.yaml"],
      "env": { "JAVA_HOME": "/path/to/jdk-25" }
    }
  }
}
```

Other MCP clients that start servers on standard input and output take the same command and arguments.

## The tools

| Tool | What it does |
| --- | --- |
| `cairn_describe` | The latest basis, every origin's stored definition as a schema document, the available rules and whether the server has their code, the problem codes, the principal, the approval policy and the clearance, with each attribute's classification in the documents. Start here. |
| `cairn_json_schema` | The JSON Schema of schema documents (`document`), of transaction payloads (`payload`), of queries (`query`), or of the values of one type (`values` with `type`). |
| `cairn_explain_problem` | The explanation of a problem code, or of a rule's code such as `lei-checksum/invalid-check-digits`. |
| `cairn_plan_schema`, `cairn_register_schema` | Plan a schema document, then register it. The plan lists each change as compatible or breaking, with hints. |
| `cairn_dry_run`, `cairn_commit` | Run a transaction payload dry, then commit it. Problems are located in the payload, with hints. |
| `cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities` | Read at an effective time, and optionally as known at a basis or a transaction time (`knownAt`). `cairn_read_entity` takes an entity's ID (`id`) or a *unique key* (`key`). |
| `cairn_timeline`, `cairn_navigate`, `cairn_traverse` | Read an entity's or relationship's history, follow one relationship name, or follow it to a maximum depth, or to the end without one. `cairn_navigate` and `cairn_traverse` start from an ID or a unique key. |
| `cairn_query` | Run a *query* written as JSON or YAML: filters, follow steps, traversals, grouping and aggregates. See [Query a store](query-a-store.md). |
| `cairn_list_changes` | Read the *change feed* after a basis, at most `limit` changes (100 by default, up to 1,000), with `next`, the basis to continue from. See [Follow the changes of a store](follow-the-changes-of-a-store.md). |
| `cairn_approval_status` | The status of an approval request and, once decided, its outcome. |

Documents, payloads and queries are passed as text, YAML or JSON, so that problems keep their lines and columns.

Every tool reads and writes through the server's clearance ([ADR-0036](../decisions/0036-classify-attributes-and-read-and-write-them-through-a-clearance-that-withholds-the-rest.md)). A state lists in `withheld` the attributes whose values the clearance did not let through, so the agent tells a withheld value from no value; what else the clearance refuses, with the problem `ACCESS_DENIED`, is in the `attribute-access` spec. A payload cannot widen the clearance. The start message on standard error names it. Give an agent only the classifications its task needs: in the HR example, the hiring agent of HR-13 has the clearance `pay` and the org-chart agent of HR-18 none.
Every tool returns JSON. A call that cannot be done returns problems instead of failing, each with a code, a path, and where it helps a hint: `{ problems }`, or `{ outcome: "rejected", problems }` for the tools that plan, register, dry-run and commit. A problem with an argument has the path `@arguments.<name>`.

A typical agent session reads `cairn_describe`, asks `cairn_json_schema` for the payload schema, writes a payload, runs it with `cairn_dry_run` until it has no problems, and then commits it. To answer questions over many entities, it asks `cairn_json_schema` for the kind `query` and sends queries to `cairn_query`.
For a schema change it edits the document from `cairn_describe`, raises the origin's version, plans and registers it.
Payloads, `cairn_read_entity`, `cairn_navigate` and `cairn_traverse` name an existing entity by its ID, or by a unique key, such as `{ "entityType": "Account", "key": { "number": "A-1" } }`, so an agent rarely needs a query to find an ID. The store resolves a write's key when it commits, at the start of the write's period ([ADR-0026](../decisions/0026-refer-to-entities-by-unique-keys-resolved-at-commit.md)).

## Rules

The server runs the Java rules it finds on its class path through the service loader: a jar that lists its rules in `META-INF/services/io.github.enricoag1982.cairn.data.Rule`.
Every other rule that a `--schema` document names becomes a *declared rule*: the server declares it without code, so that the document registers, and rejects writes to the types that name it with `RULE_UNAVAILABLE`.
The start message on standard error lists the declared rules; check it, because a misspelled rule name ends up there too.
A document registered later through `cairn_register_schema` gets no such help: a rule it names must be available.

The installed server has no rules. With the reference model, writes of Organization (rule `lei-checksum`) and Owns (rule `total-ownership-at-most-100`) are rejected, and so is Employs, whose source is an Organization. Person, Address, Account and the other relationship types work.
To run your rules, start the server class with your jar on the class path:

```sh
java -cp "cairn-agent-tools/build/install/cairn-agent-tools/lib/*:my-rules.jar" \
    io.github.enricoag1982.cairn.agents.CairnAgentServer --principal agent:demo --schema my-model.cairn.yaml
```

## Approval

With `--policy schema=approve` or `data=approve`, a registration or commit that would apply waits as an *approval request*, and the tool returns the request instead. The request keeps the agent's clearance, and approving it commits with that clearance, never a wider one.
A request that would be rejected now, or a registration that would change nothing, is answered at once. `cairn_approval_status` shows whether a person has decided, for requests that an agent with the same principal and clearance made.

People approve and reject requests through the Java API (`ApprovalQueue.pending()`, `approve` and `reject`), which the standalone server does not expose.
To let people decide, embed the adapter in your application. Put `cairn-agent-tools` and its dependencies on the class path, not the module path: the MCP SDK's jars have no valid module names. The transport is any `McpServerTransportProvider` of the MCP Java SDK, such as `StdioServerTransportProvider`.

```java
Store store = Stores.inMemory(StoreSettings.builder().discoverRules().build());
ApprovalQueue queue = new ApprovalQueue(store);
AgentPolicy policy = new AgentPolicy(AgentPolicy.Decision.REQUIRE_APPROVAL, AgentPolicy.Decision.REQUIRE_APPROVAL);
AgentTools tools = new AgentTools(store, policy, queue, "agent:onboarding", Optional.empty(), Clearance.none());
McpSyncServer server = McpAdapter.serve(tools, transportProvider);
// Later, a person:
queue.approve(queue.pending().getFirst().id(), "alice");
```

With the standalone server, `approve` is useful to see what an agent experiences; `deny` keeps an agent to reading and dry runs.
