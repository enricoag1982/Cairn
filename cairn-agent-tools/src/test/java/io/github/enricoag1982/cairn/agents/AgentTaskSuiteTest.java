package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent task suite (ADR-0017, confirmation; design D8 of {@code add-agent-tools}): scripted agents that do a task
 * with the tools alone. Each script uses nothing but what the tools return: the schema as the documents that
 * {@code cairn_describe} gives, the JSON Schemas, the IDs in receipts, and the problems with their hints. No language
 * model is involved; the script plays what an agent would do. Only a person's approval is done in Java, as it would be.
 */
class AgentTaskSuiteTest {

    private static final String MARCH_1 = "2026-03-01T00:00:00Z";
    private static final Pattern DID_YOU_MEAN = Pattern.compile("Did you mean '([^']+)'\\?");

    private final AgentFixture fixture = new AgentFixture();

    // ------------------------------------------------------------------ what an agent can do with the tools

    private JsonNode call(String tool, ObjectNode arguments) {
        return fixture.call(tool, arguments);
    }

    /** The principal that the tools say they act as. */
    private String principal() {
        return fixture.call("cairn_describe").get("principal").stringValue();
    }

    /** The document of an origin, as the tools describe it. */
    private String document(String origin) {
        for (JsonNode node : fixture.call("cairn_describe").get("origins")) {
            if (node.get("name").stringValue().equals(origin)) {
                return node.get("document").stringValue();
            }
        }
        throw new AssertionError("The store describes no origin " + origin);
    }

    private static ObjectNode values(Object... namesAndValues) {
        ObjectNode values = Json.object();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String name = (String) namesAndValues[i];
            switch (namesAndValues[i + 1]) {
                case Integer number -> values.put(name, number);
                case String text -> values.put(name, text);
                default -> throw new IllegalArgumentException(String.valueOf(namesAndValues[i + 1]));
            }
        }
        return values;
    }

    private static ObjectNode create(String temporaryId, String entityType, String from, ObjectNode values) {
        ObjectNode create = Json.object();
        create.putObject("create").put("temporaryId", temporaryId).put("entityType", entityType).put("from", from)
                .set("values", values);
        return create;
    }

    private static ObjectNode relate(String type, String source, String target, String from, ObjectNode values) {
        ObjectNode relate = Json.object();
        relate.putObject("relate").put("relationshipType", type).put("source", source).put("target", target)
                .put("from", from).set("values", values);
        return relate;
    }

    private String transaction(String principal, ObjectNode... writes) {
        ObjectNode payload = Json.object().put("cairn", 1).put("principal", principal);
        ArrayNode list = payload.putArray("writes");
        for (ObjectNode write : writes) {
            list.add(write);
        }
        return Json.render(payload);
    }

    /** Commits a payload that must go through, and returns its receipt. */
    private JsonNode commit(String payload) {
        JsonNode result = call("cairn_commit", args("payload", payload));
        assertThat(result.get("outcome").stringValue()).as(result.toString()).isEqualTo("committed");
        return result.get("receipt");
    }

    /** The ID that a receipt gives to a temporary ID of an entity. */
    private static String entityId(JsonNode receipt, String temporaryId) {
        return receipt.get("temporaryIds").get("$" + temporaryId).get("entity").stringValue();
    }

    /**
     * Repairs a payload from a problem, as an agent does from what the problem says: the expected principal, or the
     * closest name that the hint suggests in place of the name that was found.
     */
    private static String repair(String payload, JsonNode problem) {
        String code = problem.get("code").stringValue();
        if (code.equals("PRINCIPAL_MISMATCH")) {
            return payload.replace("\"principal\":\"" + problem.get("found").stringValue() + "\"",
                    "\"principal\":\"" + problem.get("expected").stringValue() + "\"");
        }
        Matcher suggestion = DID_YOU_MEAN.matcher(problem.has("hint") ? problem.get("hint").stringValue() : "");
        assertThat(suggestion.find()).as("a suggestion in %s", problem).isTrue();
        return payload.replace("\"" + problem.get("found").stringValue() + "\"",
                "\"" + suggestion.group(1) + "\"");
    }

    // ------------------------------------------------------------------ the tasks

    @Test
    @DisplayName("Task: Add an optional attribute to Person")
    void addAnOptionalAttributeToPerson() {
        // Read the schema, and the format a document has.
        String document = document("reference-model");
        JsonNode documentSchema = call("cairn_json_schema", args("kind", "document"));
        assertThat(documentSchema.get("$id").stringValue()).isEqualTo("urn:cairn:schema-document:1");
        assertThat(documentSchema.toString()).contains("attributes");
        assertThat(fixture.call("cairn_json_schema", args("kind", "values", "type", "Person"))
                .get("properties").has("email")).isFalse();

        // Edit the document as text: a new attribute, and a higher version.
        String edited = document.replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n",
                        "      birthDate:\n        type: date\n      email:\n        type: string\n"
                                + "        description: Where to write to the person.\n");
        assertThat(edited).isNotEqualTo(document);

        // Plan it, register it, and read the result back.
        JsonNode plan = call("cairn_plan_schema", args("document", edited));
        assertThat(plan.get("outcome").stringValue()).isEqualTo("wouldApply");
        assertThat(plan.get("changes")).singleElement().satisfies(change -> {
            assertThat(change.get("path").stringValue()).isEqualTo("Person.email");
            assertThat(change.get("compatible").booleanValue()).isTrue();
        });
        JsonNode registration = call("cairn_register_schema",
                args("document", edited).put("reason", "People need an email address."));
        assertThat(registration.get("outcome").stringValue()).isEqualTo("applied");
        assertThat(document("reference-model")).contains("email:").contains("version: \"1.1.0\"");
        JsonNode personValues = call("cairn_json_schema", args("kind", "values", "type", "Person"));
        assertThat(personValues.get("properties").get("email").get("description").stringValue())
                .isEqualTo("Where to write to the person.");

        // Use it.
        JsonNode receipt = commit(transaction(principal(), create("alice", "Person", "2026-01-01T00:00:00Z",
                values("name", "Alice", "email", "alice@example.com"))));
        JsonNode people = call("cairn_list_entities", args("type", "Person", "effectiveAt", MARCH_1));
        assertThat(people.get("result")).singleElement().satisfies(person -> {
            assertThat(person.get("id").stringValue()).isEqualTo(entityId(receipt, "alice"));
            assertThat(person.get("values").get("email").stringValue()).isEqualTo("alice@example.com");
        });
    }

    @Test
    @DisplayName("Task: Record that an organization owns another")
    void recordThatAnOrganizationOwnsAnother() {
        String principal = principal();
        assertThat(call("cairn_json_schema", args("kind", "payload")).get("properties").has("writes")).isTrue();
        JsonNode parties = commit(transaction(principal,
                create("acme", "Organization", "2026-01-01T00:00:00Z", values("name", "ACME")),
                create("beta", "Organization", "2026-01-01T00:00:00Z", values("name", "Beta"))));
        String acme = entityId(parties, "acme");
        String beta = entityId(parties, "beta");

        // A share of 150% is rejected, and the problem says what a share may be.
        String tooMuch = transaction(principal, relate("Owns", acme, beta, "2026-03-01T00:00:00Z", values("share", 150)));
        JsonNode first = call("cairn_dry_run", args("payload", tooMuch));
        assertThat(first.get("outcome").stringValue()).isEqualTo("rejected");
        JsonNode outOfRange = first.get("problems").get(0);
        assertThat(outOfRange.get("code").stringValue()).isEqualTo("VALUE_OUT_OF_RANGE");
        assertThat(outOfRange.get("expected").stringValue()).contains("100");
        assertThat(outOfRange.get("location").get("pointer").stringValue()).startsWith("/writes/0");

        // 30% is within the range.
        String ownership = tooMuch.replace("\"share\":150", "\"share\":30");
        assertThat(call("cairn_dry_run", args("payload", ownership)).get("outcome").stringValue())
                .isEqualTo("wouldCommit");
        JsonNode receipt = commit(ownership);
        String owns = receipt.get("created").get(0).get("relationship").stringValue();

        // Read it back from both sides.
        JsonNode owners = call("cairn_navigate", args("id", beta, "name", "ownedBy").put("effectiveAt", MARCH_1));
        assertThat(owners.get("result")).singleElement().satisfies(link -> {
            assertThat(link.get("entity").get("id").stringValue()).isEqualTo(acme);
            assertThat(link.get("relationship").get("id").stringValue()).isEqualTo(owns);
            assertThat(link.get("relationship").get("values").get("share").decimalValue()).isEqualByComparingTo("30");
        });
        JsonNode owned = call("cairn_navigate", args("id", acme, "name", "Owns").put("effectiveAt", MARCH_1));
        assertThat(owned.get("result").get(0).get("entity").get("id").stringValue()).isEqualTo(beta);
    }

    @Test
    @DisplayName("Task: Stay within 100 percent, with the help of the rule's explanation")
    void stayWithinOneHundredPercent() {
        String principal = principal();
        JsonNode parties = commit(transaction(principal,
                create("acme", "Organization", "2026-01-01T00:00:00Z", values("name", "ACME")),
                create("beta", "Organization", "2026-01-01T00:00:00Z", values("name", "Beta")),
                create("gamma", "Organization", "2026-01-01T00:00:00Z", values("name", "Gamma"))));
        String acme = entityId(parties, "acme");
        String beta = entityId(parties, "beta");
        String gamma = entityId(parties, "gamma");
        commit(transaction(principal, relate("Owns", acme, beta, "2026-03-01T00:00:00Z", values("share", 30))));

        String tooMuch = transaction(principal, relate("Owns", gamma, beta, "2026-03-01T00:00:00Z", values("share", 80)));
        JsonNode rejected = call("cairn_dry_run", args("payload", tooMuch));

        JsonNode violation = rejected.get("problems").get(0);
        assertThat(violation.get("code").stringValue()).isEqualTo("RULE_VIOLATION");
        String ruleCode = violation.get("ruleCode").stringValue();
        assertThat(ruleCode).isEqualTo("total-ownership-at-most-100/over-100-percent");
        // The explanation of the rule's code says what to do; the description of the store has the rule too.
        JsonNode explanation = call("cairn_explain_problem", args("code", ruleCode));
        assertThat(explanation.get("explanation").stringValue()).contains("Lower a share");
        assertThat(call("cairn_describe", Json.object()).get("rules").toString()).contains(ruleCode);
        String fixed = tooMuch.replace("\"share\":80", "\"share\":70");
        assertThat(call("cairn_dry_run", args("payload", fixed)).get("outcome").stringValue()).isEqualTo("wouldCommit");
        commit(fixed);
        JsonNode owners = call("cairn_navigate", args("id", beta, "name", "ownedBy").put("effectiveAt", MARCH_1));
        assertThat(owners.get("result")).hasSize(2);
    }

    @Test
    @DisplayName("Task: Correct a payload from the problems and their hints")
    void correctAPayloadFromTheProblemsAndTheirHints() {
        String payload = transaction("alice", create("acme", "Organisation", "2026-01-01T00:00:00Z",
                values("nmae", "ACME")));
        List<String> seen = new ArrayList<>();

        JsonNode result = call("cairn_dry_run", args("payload", payload));
        while (result.get("outcome").stringValue().equals("rejected") && seen.size() < 10) {
            JsonNode problem = result.get("problems").get(0);
            seen.add(problem.get("code").stringValue());
            payload = repair(payload, problem);
            result = call("cairn_dry_run", args("payload", payload));
        }

        assertThat(seen).containsExactly("PRINCIPAL_MISMATCH", "UNKNOWN_ENTITY_TYPE", "UNKNOWN_ATTRIBUTE");
        assertThat(result.get("outcome").stringValue()).isEqualTo("wouldCommit");
        JsonNode receipt = commit(payload);
        JsonNode acme = call("cairn_read_entity", args("id", entityId(receipt, "acme")).put("effectiveAt", MARCH_1));
        assertThat(acme.get("result").get("values").get("name").stringValue()).isEqualTo("ACME");
        assertThat(acme.get("result").get("entityType").stringValue()).isEqualTo("Organization");
    }

    @Test
    @DisplayName("Task: Avoid a breaking change by following the hint")
    void avoidABreakingChangeByFollowingTheHint() {
        String document = document("reference-model");
        String removed = document.replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("    attributes:\n      birthDate:\n        type: date\n", "");
        assertThat(removed).isNotEqualTo(document);

        // Removing the attribute is rejected, with the place and a hint.
        JsonNode rejected = call("cairn_register_schema", args("document", removed));
        assertThat(rejected.get("outcome").stringValue()).isEqualTo("rejected");
        JsonNode breaking = rejected.get("problems").get(0);
        assertThat(breaking.get("code").stringValue()).isEqualTo("BREAKING_CHANGE");
        assertThat(breaking.get("path").stringValue()).isEqualTo("Person.birthDate");
        assertThat(breaking.get("hint").stringValue()).contains("deprecated");
        int line = breaking.get("location").get("line").intValue();
        assertThat(removed.split("\n")[line - 1]).contains("Person:");

        // The hint says to deprecate it instead.
        String deprecated = document.replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n", "      birthDate:\n        type: date\n        deprecated: true\n");
        assertThat(call("cairn_plan_schema", args("document", deprecated)).get("outcome").stringValue())
                .isEqualTo("wouldApply");
        assertThat(call("cairn_register_schema", args("document", deprecated)).get("outcome").stringValue())
                .isEqualTo("applied");
        assertThat(document("reference-model")).contains("deprecated: true");
    }

    @Test
    @DisplayName("Task: Find out what was known when")
    void findOutWhatWasKnownWhen() {
        String principal = principal();
        JsonNode parties = commit(transaction(principal,
                create("acme", "Organization", "2026-01-01T00:00:00Z", values("name", "ACME")),
                create("beta", "Organization", "2026-01-01T00:00:00Z", values("name", "Beta"))));
        String acme = entityId(parties, "acme");
        String beta = entityId(parties, "beta");
        JsonNode ownership = commit(transaction(principal,
                relate("Owns", acme, beta, "2026-02-01T00:00:00Z", values("share", 60))));
        String owns = ownership.get("created").get(0).get("relationship").stringValue();
        long believed = ownership.get("basis").longValue();
        ObjectNode patch = Json.object();
        patch.putObject("patch").put("target", owns).put("from", "2026-02-01T00:00:00Z").set("set", values("share", 55));
        long corrected = commit(transaction(principal, patch)).get("basis").longValue();

        JsonNode now = call("cairn_read_relationship", args("id", owns).put("effectiveAt", MARCH_1));
        ObjectNode asBelieved = args("id", owns).put("effectiveAt", MARCH_1);
        asBelieved.putObject("knownAt").put("basis", believed);
        JsonNode before = call("cairn_read_relationship", asBelieved);
        JsonNode timeline = call("cairn_timeline", args("id", owns));

        assertThat(corrected).isGreaterThan(believed);
        assertThat(now.get("basis").longValue()).isEqualTo(corrected);
        assertThat(now.get("result").get("values").get("share").decimalValue()).isEqualByComparingTo("55");
        assertThat(before.get("basis").longValue()).isEqualTo(believed);
        assertThat(before.get("result").get("values").get("share").decimalValue()).isEqualByComparingTo("60");
        assertThat(timeline.get("kind").stringValue()).isEqualTo("relationship");
        assertThat(timeline.get("result")).isNotEmpty();
        JsonNode traversal = call("cairn_traverse",
                args("id", beta, "name", "ownedBy").put("maxDepth", 5).put("effectiveAt", MARCH_1));
        assertThat(traversal.get("result")).singleElement().satisfies(step -> {
            assertThat(step.get("entity").get("id").stringValue()).isEqualTo(acme);
            assertThat(step.get("depth").intValue()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Task: Wait for a person's approval")
    void waitForAPersonsApproval() {
        AgentFixture approving = new AgentFixture(new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.REQUIRE_APPROVAL));
        String principal = approving.call("cairn_describe").get("principal").stringValue();
        assertThat(approving.call("cairn_describe").get("policy").get("dataCommits").stringValue())
                .isEqualTo("requireApproval");

        // The agent asks, and is told to wait.
        JsonNode asked = approving.call("cairn_commit", args("payload", transaction(principal,
                create("acme", "Organization", "2026-01-01T00:00:00Z", values("name", "ACME")))));
        assertThat(asked.get("outcome").stringValue()).isEqualTo("pending");
        String requestId = asked.get("request").get("id").stringValue();
        assertThat(approving.call("cairn_approval_status", args("requestId", requestId)).get("status").stringValue())
                .isEqualTo("pending");
        assertThat(approving.call("cairn_list_entities", args("type", "Organization").put("effectiveAt", MARCH_1))
                .get("result")).isEmpty();

        // A person approves it.
        approving.queue.approve(requestId, "alice");

        // The agent finds out, and what happened.
        JsonNode status = approving.call("cairn_approval_status", args("requestId", requestId));
        assertThat(status.get("status").stringValue()).isEqualTo("approved");
        String created = status.get("outcome").get("receipt").get("created").get(0).get("entity").stringValue();
        JsonNode read = approving.call("cairn_read_entity", args("id", created).put("effectiveAt", MARCH_1));
        assertThat(read.get("result").get("values").get("name").stringValue()).isEqualTo("ACME");

        // A schema change waits for a person too, and is applied when the person approves it.
        String edited = approving.documentOf("reference-model").replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n",
                        "      birthDate:\n        type: date\n      email:\n        type: string\n");
        JsonNode schemaRequest = approving.call("cairn_register_schema", args("document", edited));
        assertThat(schemaRequest.get("outcome").stringValue()).isEqualTo("pending");
        approving.queue.approve(schemaRequest.get("request").get("id").stringValue(), "alice");
        assertThat(approving.call("cairn_approval_status",
                args("requestId", schemaRequest.get("request").get("id").stringValue())).get("outcome").get("outcome")
                .stringValue()).isEqualTo("applied");
        assertThat(approving.call("cairn_json_schema", args("kind", "values", "type", "Person")).get("properties")
                .has("email")).isTrue();
    }

    // ------------------------------------------------------------------ through the protocol

    @Test
    @DisplayName("Task: Do a task through the Model Context Protocol")
    void doATaskThroughTheModelContextProtocol() throws Exception {
        InProcessTransport transport = new InProcessTransport();
        McpSyncServer server = McpAdapter.serve(fixture.tools, transport.server());
        try (McpSyncClient client = McpClient.sync(transport.client()).requestTimeout(Duration.ofSeconds(30)).build()) {
            client.initialize();

            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools).map(McpSchema.Tool::name).contains("cairn_describe", "cairn_commit",
                    "cairn_list_entities");

            JsonNode description = Json.parse(text(client.callTool(McpSchema.CallToolRequest.builder("cairn_describe")
                    .arguments(Map.of()).build())));
            String agent = description.get("principal").stringValue();
            JsonNode committed = Json.parse(text(client.callTool(McpSchema.CallToolRequest.builder("cairn_commit")
                    .arguments(Map.of("payload", transaction(agent, create("acme", "Organization",
                            "2026-01-01T00:00:00Z", values("name", "ACME"))))).build())));
            assertThat(committed.get("outcome").stringValue()).isEqualTo("committed");
            JsonNode organizations = Json.parse(text(client.callTool(McpSchema.CallToolRequest
                    .builder("cairn_list_entities")
                    .arguments(Map.of("type", "Organization", "effectiveAt", MARCH_1)).build())));
            assertThat(organizations.get("result")).singleElement().satisfies(organization ->
                    assertThat(organization.get("values").get("name").stringValue()).isEqualTo("ACME"));
        } finally {
            server.closeGracefully();
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(true);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
