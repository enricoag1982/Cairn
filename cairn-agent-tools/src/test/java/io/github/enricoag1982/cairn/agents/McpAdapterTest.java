package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.schema.Basis;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The MCP layer, with the SDK's client connected to the server in the same JVM (design D2 of {@code add-agent-tools}). */
class McpAdapterTest {

    private static final List<String> TOOLS = List.of("cairn_describe", "cairn_json_schema", "cairn_explain_problem",
            "cairn_plan_schema", "cairn_register_schema", "cairn_dry_run", "cairn_commit", "cairn_read_entity",
            "cairn_read_relationship", "cairn_list_entities", "cairn_timeline", "cairn_navigate", "cairn_traverse",
            "cairn_approval_status");

    private final AgentFixture fixture = new AgentFixture();
    private McpSyncServer server;
    private McpSyncClient client;

    private McpSyncClient connect(AgentTools tools) {
        InProcessTransport transport = new InProcessTransport();
        server = McpAdapter.serve(tools, transport.server());
        client = McpClient.sync(transport.client()).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    @AfterEach
    void close() {
        if (client != null) {
            client.closeGracefully();
        }
        if (server != null) {
            server.closeGracefully();
        }
    }

    /** The text of the one text content of a result. */
    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).singleElement().isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    @Test
    @DisplayName("Scenario: Listing the tools")
    void listingTheTools() {
        connect(fixture.tools);

        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).map(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOLS);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).as("description of %s", tool.name()).isNotBlank();
            assertThat(tool.inputSchema()).as("input schema of %s", tool.name()).containsEntry("type", "object");
        });
    }

    @Test
    @DisplayName("Scenario: A malformed payload")
    void aMalformedPayload() {
        connect(fixture.tools);
        String malformed = payload("{\"upsert\": {\"entityType\": \"Organization\", \"values\": {\"name\": \"ACME\"}}}");

        McpSchema.CallToolResult result = call("cairn_commit", Map.of("payload", malformed));

        assertThat(result.isError()).as("the protocol call succeeds").isNotEqualTo(true);
        JsonNode outcome = Json.parse(text(result));
        assertThat(outcome.get("outcome").stringValue()).isEqualTo("rejected");
        JsonNode problem = outcome.get("problems").get(0);
        assertThat(problem.get("code").stringValue()).isEqualTo("UNKNOWN_KEY");
        assertThat(problem.get("location").get("pointer").stringValue()).startsWith("/writes/0");
        assertThat(problem.get("location").get("line").intValue()).isPositive();
        assertThat(fixture.store.latestBasis()).isEqualTo(new Basis(1));
    }

    @Test
    void aToolReturnsItsJsonAsTextContent() {
        connect(fixture.tools);

        McpSchema.CallToolResult result = call("cairn_describe", Map.of());

        assertThat(result.isError()).isNotEqualTo(true);
        JsonNode description = Json.parse(text(result));
        assertThat(description.get("origins").get(0).get("name").stringValue()).isEqualTo("reference-model");
        assertThat(text(result)).isEqualTo(fixture.tools.describe("{}"));
    }

    @Test
    void aCallWithWrongArgumentsReturnsProblemsAndNotAProtocolError() {
        connect(fixture.tools);

        McpSchema.CallToolResult result = call("cairn_read_entity", Map.of("id", 42));

        assertThat(result.isError()).isNotEqualTo(true);
        JsonNode problems = Json.parse(text(result)).get("problems");
        assertThat(problems).map(problem -> problem.get("code").stringValue())
                .containsExactlyInAnyOrder("INVALID_VALUE", "MISSING_KEY");
        assertThat(problems).map(problem -> problem.get("path").stringValue())
                .containsExactlyInAnyOrder("@arguments.id", "@arguments.effectiveAt");
    }

    @Test
    void argumentsAreCheckedByTheToolsAndNotAgainstTheInputSchemaByTheProtocolLayer() {
        connect(fixture.tools);

        McpSchema.CallToolResult result = call("cairn_json_schema", Map.of("kind", "nonsense"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(Json.parse(text(result)).get("problems").get(0).get("path").stringValue())
                .isEqualTo("@arguments.kind");
    }

    @Test
    void aFailureOfTheToolItselfIsAnErrorResult() {
        Store broken = (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[] {Store.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("latestBasis")) {
                        throw new IllegalStateException("The storage is broken.");
                    }
                    return method.invoke(fixture.store, arguments);
                });
        connect(new AgentTools(broken, AgentPolicy.defaults(), new ApprovalQueue(broken), "agent:test",
                Optional.empty()));

        McpSchema.CallToolResult result = call("cairn_describe", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("cairn_describe").contains("The storage is broken.");
    }

    @Test
    void theServerNamesItselfAndTellsAnAgentWhereToStart() {
        connect(fixture.tools);

        assertThat(client.getServerInfo().name()).isEqualTo("cairn-agent-tools");
        assertThat(client.getServerInstructions()).contains("cairn_describe").contains("cairn_dry_run");
    }
}
