package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.documents.Queries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The tool {@code cairn_query} (design D5 of {@code add-queries-and-aggregation}): its arguments, results and problems. */
class AgentQueryTest {

    private static final String MARCH_31 = "2026-03-31T00:00:00Z";

    private static final String TOTAL_BALANCE = """
            {
              "from": { "type": "Account", "as": "a" },
              "steps": [
                { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } },
                { "traverse": { "from": "holder", "name": "ownedBy", "maxDepth": 10, "as": "party" } },
                { "where": { "not": { "hasLink": { "of": "party", "name": "ownedBy" } } } }
              ],
              "groupBy": [ { "of": "party" } ],
              "aggregates": [ { "function": "sum", "attribute": "balance", "of": "a", "as": "total" } ]
            }
            """;

    private final AgentFixture fixture = new AgentFixture();

    /** RD-1, RD-5 and RD-2, with the corrections of both, in the order of their dates. */
    private void recordTheStories() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(beta, acme));
        Receipt ownership = fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
        EntityId a1 = entity(accounts, A1);
        fixture.replay(ReferenceStories.rd5BalancePatch(a1));
        fixture.replay(ReferenceStories.rd5BalanceCorrection(a1));
        fixture.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
    }

    private JsonNode query(String query) {
        return fixture.call("cairn_query", args("query", query, "effectiveAt", MARCH_31));
    }

    private static List<String> codes(JsonNode answer) {
        List<String> codes = new ArrayList<>();
        answer.get("problems").forEach(problem -> codes.add(problem.get("code").stringValue()));
        return codes;
    }

    @Test
    void theToolIsInTheCatalogWithItsArguments() {
        JsonNode schema = null;
        String description = "";
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            if (tool.name().equals("cairn_query")) {
                schema = Json.parse(tool.inputSchema());
                description = tool.description();
            }
        }

        assertThat(schema).isNotNull();
        assertThat(schema.get("required")).map(JsonNode::stringValue).containsExactly("query", "effectiveAt");
        assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrder("query", "effectiveAt", "knownAt");
        assertThat(schema.get("properties").get("query").get("type").stringValue()).isEqualTo("string");
        assertThat(description).contains("cairn_json_schema").contains("located").contains("{columns, rows}");
        assertThat(AgentTools.toolDescriptions()).map(AgentTools.ToolDescription::name)
                .containsSubsequence("cairn_traverse", "cairn_query", "cairn_approval_status");
    }

    @Test
    void theJsonSchemaOfTheToolsInputKindIsThePublishedSchemaOfQueries() {
        JsonNode schema = fixture.call("cairn_json_schema", args("kind", "query"));

        assertThat(schema.get("$id").stringValue()).isEqualTo("urn:cairn:query:1");
        assertThat(Json.render(schema)).isEqualTo(Json.render(Json.parse(Queries.jsonSchema())));
    }

    @Test
    void aMisspelledKindOfJsonSchemaGetsAHintThatNamesQuery() {
        JsonNode result = fixture.call("cairn_json_schema", args("kind", "quary"));

        assertThat(codes(result)).containsExactly("INVALID_VALUE");
        assertThat(result.get("problems").get(0).get("hint").stringValue()).contains("query");
        assertThat(result.get("problems").get(0).get("expected").stringValue()).contains("query");
    }

    @Test
    void theResultHoldsTheColumnsAndRowsAndTheCoordinatesAsTheOtherReadsDo() {
        recordTheStories();

        JsonNode answer = query(TOTAL_BALANCE);

        assertThat(answer.propertyNames()).containsExactly("effectiveAt", "basis", "result");
        assertThat(answer.get("result").propertyNames()).containsExactly("columns", "rows");
        assertThat(answer.get("result").get("rows")).hasSize(1);
    }

    @Test
    void theQueryIsReadAtTheCoordinatesTheCallGives() {
        recordTheStories();
        ObjectNode knownOnApril5 = args("query", TOTAL_BALANCE, "effectiveAt", MARCH_31);
        knownOnApril5.putObject("knownAt").put("transactionTime", "2026-04-05T00:00:00Z");
        ObjectNode atBasis = args("query", TOTAL_BALANCE, "effectiveAt", MARCH_31);
        atBasis.putObject("knownAt").put("basis", 3);

        JsonNode then = fixture.call("cairn_query", knownOnApril5);
        JsonNode now = query(TOTAL_BALANCE);
        JsonNode early = fixture.call("cairn_query", atBasis);

        assertThat(then.get("result").get("rows").get(0).get(1).decimalValue()).isEqualByComparingTo("3500");
        assertThat(now.get("result").get("rows").get(0).get(1).decimalValue()).isEqualByComparingTo("3200");
        assertThat(then.get("basis").longValue()).isLessThan(now.get("basis").longValue());
        assertThat(early.get("basis").longValue()).isEqualTo(3);
        assertThat(early.get("result").get("rows")).as("before the ownership was recorded, accounts have no owner")
                .hasSize(2);
    }

    @Test
    void aQueryCanBeWrittenAsYamlAndUseTheOptionsOfTheForm() {
        recordTheStories();
        String yaml = """
                from: {type: Account, as: a}
                columns:
                  - {attribute: number, of: a, as: account}
                  - {attribute: balance, of: a}
                order:
                  - {attribute: balance, of: a, descending: true}
                limit: 1
                """;

        JsonNode answer = query(yaml);

        assertThat(answer.get("result").get("columns")).map(JsonNode::stringValue).containsExactly("account", "a.balance");
        assertThat(answer.get("result").get("rows")).singleElement().satisfies(row -> {
            assertThat(row.get(0).stringValue()).isEqualTo("A-2");
            assertThat(row.get(1).decimalValue()).isEqualByComparingTo("2000");
        });
    }

    @Test
    void anEmptyBindingIsANullCell() {
        fixture.createParties();
        String text = """
                {"from": {"type": "Organization", "as": "o"},
                 "steps": [ {"optionalFollow": {"from": "o", "name": "Employs", "as": "employee"}} ]}
                """;

        JsonNode answer = query(text);

        assertThat(answer.get("result").get("rows")).hasSize(2).allSatisfy(row -> {
            assertThat(row.get(0).get("entityType").stringValue()).isEqualTo("Organization");
            assertThat(row.get(1).isNull()).isTrue();
        });
    }

    @Test
    void aProblemOfReadingIsLocatedInTheText() {
        String text = "{\n  \"from\": { \"type\": \"Account\", \"as\": \"a\" },\n  \"steps\": [ { \"folow\": "
                + "{ \"from\": \"a\", \"name\": \"HeldBy\", \"as\": \"h\" } } ]\n}";

        JsonNode answer = query(text);

        assertThat(answer.has("result")).isFalse();
        JsonNode problem = answer.get("problems").get(0);
        assertThat(problem.get("code").stringValue()).isEqualTo("UNKNOWN_KEY");
        assertThat(problem.get("path").stringValue()).isEqualTo("@query.steps.0");
        assertThat(problem.get("hint").stringValue()).contains("follow");
        assertThat(problem.get("location").get("pointer").stringValue()).isEqualTo("/steps/0/folow");
        assertThat(problem.get("location").get("line").intValue()).isEqualTo(3);
    }

    @Test
    void aProblemOfTheQueryInTheSchemaIsLocatedAtThePartOfTheText() {
        recordTheStories();
        String text = """
                {
                  "from": { "type": "Account", "as": "a" },
                  "steps": [
                    { "where": { "hasValue": { "attribute": "balance", "of": "a" } } },
                    { "where": { "compare": { "left": { "attribute": "balanse", "of": "a" }, "op": "gt",
                                              "right": { "value": 1200 } } } }
                  ]
                }
                """;

        JsonNode answer = query(text);

        assertThat(answer.has("result")).isFalse();
        assertThat(codes(answer)).containsExactly("UNKNOWN_ATTRIBUTE");
        JsonNode problem = answer.get("problems").get(0);
        assertThat(problem.get("path").stringValue()).isEqualTo("@query.steps.1");
        assertThat(problem.get("hint").stringValue()).contains("balance");
        assertThat(problem.get("location").get("pointer").stringValue()).isEqualTo("/steps/1");
        assertThat(problem.get("location").get("line").intValue()).isEqualTo(5);
    }

    @Test
    void everyProblemOfAQueryIsReportedAtOnce() {
        String text = """
                {
                  "from": { "type": "Acount", "as": "a" },
                  "steps": [ { "follow": { "from": "a", "name": "HeldBye", "as": "h" } } ],
                  "columns": [ { "binding": "nobody" } ]
                }
                """;

        JsonNode answer = query(text);

        assertThat(codes(answer)).containsExactly("UNKNOWN_ENTITY_TYPE", "UNKNOWN_RELATIONSHIP_TYPE", "UNKNOWN_BINDING");
        assertThat(answer.get("problems")).map(problem -> problem.get("path").stringValue())
                .containsExactly("@query.from", "@query.steps.0", "@query.columns.0");
        assertThat(answer.get("problems")).allSatisfy(problem -> assertThat(problem.has("location")).isTrue());
    }

    @Test
    void anAggregateThatCannotBeComputedIsAProblemInTheStep() {
        fixture.createParties();
        String text = """
                {"from": {"type": "Party", "as": "p"},
                 "aggregates": [ {"function": "sum", "attribute": "name", "of": "p", "as": "total"} ]}
                """;

        JsonNode answer = query(text);

        assertThat(codes(answer)).containsExactly("INVALID_AGGREGATE");
        assertThat(answer.get("problems").get(0).get("message").stringValue()).contains("sum needs numbers");
    }

    @Test
    void wrongArgumentsAreProblemsOfTheArguments() {
        JsonNode noQuery = fixture.call("cairn_query", args("effectiveAt", MARCH_31));
        JsonNode noTime = fixture.call("cairn_query", args("query", TOTAL_BALANCE));
        JsonNode notText = fixture.call("cairn_query", Json.object().put("query", 5).put("effectiveAt", MARCH_31));
        JsonNode unknown = fixture.call("cairn_query", args("query", TOTAL_BALANCE, "effectiveAt", MARCH_31, "limit", "1"));
        JsonNode badTime = fixture.call("cairn_query", args("query", TOTAL_BALANCE, "effectiveAt", "yesterday"));

        assertThat(noQuery.get("problems").get(0).get("path").stringValue()).isEqualTo("@arguments.query");
        assertThat(codes(noQuery)).containsExactly("MISSING_KEY");
        assertThat(noTime.get("problems").get(0).get("path").stringValue()).isEqualTo("@arguments.effectiveAt");
        assertThat(notText.get("problems").get(0).get("path").stringValue()).isEqualTo("@arguments.query");
        assertThat(codes(notText)).containsExactly("INVALID_VALUE");
        assertThat(codes(unknown)).containsExactly("UNKNOWN_KEY");
        assertThat(badTime.get("problems").get(0).get("path").stringValue()).isEqualTo("@arguments.effectiveAt");
    }

    @Test
    void aTextThatIsNoQueryIsASyntaxProblemOfTheText() {
        JsonNode answer = query("{ \"from\": ");

        assertThat(codes(answer)).containsExactly("DOCUMENT_SYNTAX");
        assertThat(answer.get("problems").get(0).get("location").get("line").intValue()).isPositive();
    }

    @Test
    void theToolAnswersWithAJsonObjectWhateverTheQuery() {
        for (String text : new String[] {"", "{}", "[]", "null", "42", "{\"from\": 1}", "---\n---\n", "{\"from\": {\"type\": "
                + "\"X\", \"as\": \"x\"}, \"limit\": -5}", "{\"from\": {\"type\": \"Account\", \"as\": \"a\"}, \"order\": "
                + "[{\"binding\": \"a\"}], \"steps\": [{\"traverse\": {\"from\": \"a\", \"name\": \"HeldBy\", \"as\": "
                + "\"b\", \"maxDepth\": 2147483647}}]}"}) {
            ObjectNode arguments = args("query", text, "effectiveAt", MARCH_31);

            JsonNode answer = fixture.call("cairn_query", arguments);

            assertThat(answer.isObject()).as(text).isTrue();
        }
    }
}
