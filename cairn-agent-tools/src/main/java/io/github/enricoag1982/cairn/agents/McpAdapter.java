package io.github.enricoag1982.cairn.agents;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Serves {@link AgentTools} as tools of the Model Context Protocol, with the synchronous server of the MCP Java SDK
 * (ADR-0007, design D2 of {@code add-agent-tools}, capability {@code agent-tools}). The layer is thin: it registers
 * every tool with its name, description and input JSON Schema, and a call that passes the arguments to the tool as JSON
 * text and returns the tool's JSON as text content.
 *
 * <p>The tools report problems in their results, so a call that has problems is a successful protocol call: the result
 * is not marked as an error. Only a failure of the tool itself, a bug, is. The SDK is told not to validate arguments
 * against the input schemas, because the tools check their arguments and report what is wrong with a located problem,
 * which an agent can act on better than a protocol error.
 */
public final class McpAdapter {

    /** The name that the server reports to clients. */
    public static final String SERVER_NAME = "cairn-agent-tools";

    private static final String DEFAULT_VERSION = "0.1.0-SNAPSHOT";

    private static final String INSTRUCTIONS = """
            Cairn is a system of record. These tools read and change one store.
            Start with cairn_describe: it returns the schema as YAML schema documents, the rules, the problem codes, \
            the approval policy and the principal you act as.
            To change the schema: take the document from cairn_describe, edit it, raise the version of its origin, call \
            cairn_plan_schema, then cairn_register_schema. Breaking changes are rejected.
            To write data: build a transaction payload from the JSON Schema of cairn_json_schema (kind payload), with \
            your principal; call cairn_dry_run until it says wouldCommit, then cairn_commit.
            Problems come back as JSON results, not as protocol errors: each has a code, a path, where it is in your \
            document or payload (line and column), what was expected, what was found and a hint. Fix them and try \
            again; cairn_explain_problem explains a code.
            A request that needs a person's approval comes back as pending with an approval request: poll \
            cairn_approval_status.
            Read data with cairn_read_entity, cairn_read_relationship, cairn_list_entities, cairn_timeline, \
            cairn_navigate and cairn_traverse; every read says the effective time and the basis it was made at.""";

    private McpAdapter() {
    }

    /**
     * Serves the tools over a transport, with the synchronous server of the SDK. The server starts to talk on the
     * transport when it is built, and runs until it is closed.
     *
     * @param tools     the tools to serve
     * @param transport the transport, for example the SDK's {@code StdioServerTransportProvider}
     * @return the server; close it to end the session
     */
    public static McpSyncServer serve(AgentTools tools, McpServerTransportProvider transport) {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(transport, "transport");
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        String version = McpAdapter.class.getPackage().getImplementationVersion();
        McpServer.SyncSpecification<?> server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, version == null ? DEFAULT_VERSION : version)
                .instructions(INSTRUCTIONS)
                .validateToolInputs(false);
        for (AgentTools.ToolDescription description : AgentTools.toolDescriptions()) {
            McpSchema.Tool tool = McpSchema.Tool.builder(description.name(), mapper, description.inputSchema())
                    .description(description.description())
                    .build();
            server.tools(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> call(tools, mapper, description.name(), request.arguments()))
                    .build());
        }
        return server.build();
    }

    private static McpSchema.CallToolResult call(AgentTools tools, McpJsonMapper mapper, String name,
            Map<String, Object> arguments) {
        try {
            String json = tools.call(name, mapper.writeValueAsString(arguments == null ? Map.of() : arguments));
            return McpSchema.CallToolResult.builder().addTextContent(json).isError(false).build();
        } catch (IOException | RuntimeException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("The tool " + name + " failed: " + e)
                    .isError(true)
                    .build();
        }
    }
}
