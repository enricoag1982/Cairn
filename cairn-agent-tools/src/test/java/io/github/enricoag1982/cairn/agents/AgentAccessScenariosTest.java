package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The scenarios of the {@code attribute-access} spec about agents, one test each (ADR-0036, design D5 of
 * {@code add-attribute-access-control}): the tools of a server that starts without a clearance, and with one. Account's
 * {@code balance} is classified {@code finance} in these tests only, and the transactions of RD-1 and RD-5 are recorded in
 * the store directly, because the agent without a clearance could not write the balances. The clearance is the one that
 * the options of the server give.
 */
class AgentAccessScenariosTest {

    private static final String MARCH_31 = "2026-03-31T00:00:00Z";

    /** The query for the Accounts with a balance above 1,000, in the form of the published JSON Schema of queries. */
    private static final String BALANCE_ABOVE_1000 = """
            {
              "from": { "type": "Account", "as": "a" },
              "steps": [
                { "where": { "compare": { "left": { "attribute": "balance", "of": "a" }, "op": "gt",
                                          "right": { "value": 1000 } } } }
              ]
            }
            """;

    private static SchemaDefinition finance() {
        return Definitions.withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
    }

    /** The tools of a server, and the ID of A-1 in its store. */
    private record Server(AgentFixture tools, EntityId a1) {
        JsonNode call(String tool, ObjectNode arguments) {
            return tools.call(tool, arguments);
        }
    }

    /** The tools of a server started with these options, over a store of RD-1 and RD-5 whose balance is classified. */
    private static Server serverStartedWith(String... options) throws Exception {
        String[] args = new String[options.length + 2];
        args[0] = "--principal";
        args[1] = "agent:demo";
        System.arraycopy(options, 0, args, 2, options.length);
        AgentFixture fixture = new AgentFixture(finance(), ServerOptions.parse(args).clearance());
        Receipt parties = fixture.createParties();
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        return new Server(fixture, entity(accounts, A1));
    }

    @Test
    @DisplayName("Scenario: An agent without a clearance")
    void anAgentWithoutAClearance() throws Exception {
        Server server = serverStartedWith();

        JsonNode read = server.call("cairn_read_entity", args("id", server.a1().value(), "effectiveAt", MARCH_31));

        JsonNode result = read.get("result");
        assertThat(result.get("values").get("number").stringValue()).isEqualTo("A-1");
        assertThat(result.get("values").get("currency").stringValue()).isEqualTo("EUR");
        assertThat(result.get("values").has("balance")).as("no balance").isFalse();
        assertThat(result.get("withheld")).map(JsonNode::stringValue).containsExactly("balance");
        assertThat(read.has("problems")).isFalse();
    }

    @Test
    @DisplayName("Scenario: An agent's query over a withheld attribute")
    void anAgentsQueryOverAWithheldAttribute() throws Exception {
        Server server = serverStartedWith();

        JsonNode result = server.call("cairn_query", args("query", BALANCE_ABOVE_1000, "effectiveAt", MARCH_31));

        assertThat(result.has("result")).isFalse();
        assertThat(result.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("@query.steps.0");
            assertThat(problem.get("location").get("pointer").stringValue()).startsWith("/steps/0");
        });
    }

    @Test
    @DisplayName("Scenario: An agent with a clearance")
    void anAgentWithAClearance() throws Exception {
        Server server = serverStartedWith("--clearance", "finance");

        JsonNode read = server.call("cairn_read_entity", args("id", server.a1().value(), "effectiveAt", MARCH_31));

        JsonNode result = read.get("result");
        assertThat(result.get("values").get("balance").decimalValue()).isEqualByComparingTo("1000");
        assertThat(result.get("values").get("number").stringValue()).isEqualTo("A-1");
        assertThat(result.has("withheld")).as("nothing withheld").isFalse();
    }

    @Test
    @DisplayName("Scenario: An agent that would declassify the balance")
    void anAgentThatWouldDeclassifyTheBalance() throws Exception {
        Server server = serverStartedWith();
        String document = SchemaDocuments.writeYaml(ReferenceModel.origin("1.1.0"), ReferenceModel.definition());

        JsonNode result = server.call("cairn_register_schema", args("document", document));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(result.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("Account.balance");
            assertThat(problem.get("location").get("pointer").stringValue())
                    .startsWith("/entityTypes/Account/attributes/balance");
        });
        assertThat(server.tools().store.schema().current().entityType("Account").orElseThrow().attribute("balance")
                .orElseThrow().classification()).as("balance is still classified").hasValue("finance");
        assertThat(server.tools().store.schema().history()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: An agent's check over a withheld balance")
    void anAgentsCheckOverAWithheldBalance() throws Exception {
        Server server = serverStartedWith();
        String document = SchemaDocuments.writeYaml(ReferenceModel.origin("1.1.0"),
                Definitions.withCheck(finance(), "Account", "balance-above-1000", "self.balance > 1000.0", null));

        JsonNode result = server.call("cairn_register_schema", args("document", document));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(result.get("problems")).anySatisfy(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("Account.balance-above-1000");
        });
        assertThat(server.tools().store.schema().current().entityType("Account").orElseThrow().effectiveChecks())
                .isEmpty();
        assertThat(server.tools().store.schema().history()).hasSize(1);
    }

    @Test
    void theAgentWithAClearanceCanRunTheQueryTheAgentWithoutOneCannot() throws Exception {
        Server server = serverStartedWith("--clearance", "finance");

        JsonNode result = server.call("cairn_query", args("query", BALANCE_ABOVE_1000, "effectiveAt", MARCH_31));

        assertThat(result.has("problems")).isFalse();
        assertThat(result.get("result").get("columns")).map(JsonNode::stringValue).containsExactly("a");
        assertThat(result.get("result").get("rows")).hasSize(1);
        assertThat(Clearance.of("finance")).isEqualTo(ServerOptions.parse(new String[] {"--principal", "p",
                "--clearance", "finance"}).clearance());
    }
}
