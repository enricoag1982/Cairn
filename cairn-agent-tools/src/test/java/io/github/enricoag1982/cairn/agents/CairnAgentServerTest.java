package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.createOrganization;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The server, started as a client starts it (design D7 of {@code add-agent-tools}). */
@Timeout(60)
class CairnAgentServerTest {

    /** The schema document of the reference domain, as it is in {@code docs/reference}. */
    private static final Path REFERENCE_MODEL = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-model.cairn.yaml");

    @TempDir
    Path directory;

    private static String model() {
        return REFERENCE_MODEL.toString();
    }

    @Test
    @DisplayName("Scenario: Starting a server with the reference model")
    void startingAServerWithTheReferenceModel() throws Exception {
        try (StdioSession session = new StdioSession("--schema", model(), "--principal", "agent:demo")) {
            session.initialize();

            JsonNode description = session.callTool("cairn_describe", Json.object());

            assertThat(description.get("origins")).singleElement().satisfies(origin -> {
                assertThat(origin.get("name").stringValue()).isEqualTo("reference-model");
                assertThat(origin.get("version").stringValue()).isEqualTo("1.0.0");
            });
            assertThat(description.get("principal").stringValue()).isEqualTo("agent:demo");
            assertThat(session.finish()).isZero();
        }
    }

    @Test
    void theServerSpeaksTheProtocolOnStandardOutputAndNothingElse() throws Exception {
        try (StdioSession session = new StdioSession("--schema", model(), "--principal", "agent:demo")) {
            JsonNode initialized = session.initialize();
            JsonNode tools = session.request("tools/list", "{}");

            assertThat(initialized.get("serverInfo").get("name").stringValue()).isEqualTo("cairn-agent-tools");
            assertThat(initialized.get("instructions").stringValue()).contains("cairn_describe");
            assertThat(tools.get("tools")).hasSize(15);
            assertThat(session.finish()).isZero();
        }
    }

    @Test
    void aProblemInAToolCallIsAResultAndNotAProtocolError() throws Exception {
        try (StdioSession session = new StdioSession("--principal", "agent:demo")) {
            session.initialize();

            JsonNode result = session.callTool("cairn_commit", args("payload", "{\"cairn\": 1, \"writes\": 5}"));

            assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
            assertThat(result.get("problems")).isNotEmpty();
        }
    }

    /** The description of a rule among the rules {@code cairn_describe} lists. */
    private static JsonNode rule(JsonNode description, String name) {
        for (JsonNode rule : description.get("rules")) {
            if (rule.get("name").stringValue().equals(name)) {
                return rule;
            }
        }
        throw new AssertionError("No rule " + name + " in " + description.get("rules"));
    }

    @Test
    void theRulesThatTheReferenceModelNamesAreDeclaredWithoutCodeAndWritesThatNeedThemAreRejected() throws Exception {
        try (StdioSession session = new StdioSession("--schema", model(), "--principal", "agent:demo")) {
            session.initialize();

            JsonNode description = session.callTool("cairn_describe", Json.object());
            JsonNode commit = session.callTool("cairn_commit", args("payload",
                    AgentFixture.payloadOf("agent:demo", createOrganization("ACME"))));

            JsonNode lei = rule(description, "lei-checksum");
            JsonNode ownership = rule(description, "total-ownership-at-most-100");
            assertThat(lei.get("hasCode").booleanValue()).isFalse();
            assertThat(lei.get("appliesTo").stringValue()).isEqualTo("Organization");
            assertThat(lei.get("codes")).isEmpty();
            assertThat(lei.get("description").stringValue()).contains("reference-model.cairn.yaml");
            assertThat(ownership.get("hasCode").booleanValue()).isFalse();
            assertThat(ownership.get("appliesTo").stringValue()).isEqualTo("Owns");
            assertThat(commit.get("problems").get(0).get("code").stringValue()).isEqualTo("RULE_UNAVAILABLE");
            assertThat(session.finish()).isZero();
        }
    }

    @Test
    void aRuleOnTheClassPathRunsAndIsNotDeclared() throws Exception {
        Path leads = Files.writeString(directory.resolve("leads.cairn.yaml"), """
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Lead:
                    attributes:
                      source: { type: string }
                    rules:
                      - { name: lead-has-source, version: 1 }
                """);
        try (StdioSession session = new StdioSession("--schema", leads.toString(), "--principal", "agent:demo")) {
            session.initialize();
            String withoutSource = AgentFixture.payloadOf("agent:demo",
                    "{\"create\": {\"entityType\": \"Lead\", \"from\": \"2026-01-01T00:00:00Z\"}}");
            String withSource = AgentFixture.payloadOf("agent:demo", "{\"create\": {\"entityType\": \"Lead\", "
                    + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": {\"source\": \"A fair\"}}}");

            JsonNode description = session.callTool("cairn_describe", Json.object());
            JsonNode rejected = session.callTool("cairn_commit", args("payload", withoutSource));
            JsonNode committed = session.callTool("cairn_commit", args("payload", withSource));

            JsonNode rule = rule(description, "lead-has-source");
            assertThat(rule.get("hasCode").booleanValue()).isTrue();
            assertThat(rule.get("codes").get(0).get("code").stringValue()).isEqualTo("lead-has-source/missing-source");
            assertThat(rejected.get("problems").get(0).get("ruleCode").stringValue())
                    .isEqualTo("lead-has-source/missing-source");
            assertThat(committed.get("outcome").stringValue()).isEqualTo("committed");
        }
    }

    @Test
    void aDocumentThatNamesAMadeUpRuleRegistersAtStartAndTheRuleIsDeclaredWithoutCode() throws Exception {
        Path scored = Files.writeString(directory.resolve("scored.cairn.yaml"), """
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Lead:
                    rules:
                      - { name: lead-score, version: 3 }
                """);

        Store store = CairnAgentServer.createStore(ServerOptions.parse(new String[] {"--principal", "agent:demo",
                "--schema", scored.toString()}));

        assertThat(store.schema().current().origins()).extracting(origin -> origin.name()).containsExactly("crm");
        assertThat(store.declaredRules()).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("lead-score");
            assertThat(rule.version()).isEqualTo(3);
            assertThat(rule.appliesTo()).isEqualTo("Lead");
            assertThat(rule.codes()).isEmpty();
            assertThat(rule.description())
                    .isEqualTo("Named by the schema document scored.cairn.yaml; the server has no code for this rule.");
        });
        try (StdioSession session = new StdioSession("--schema", scored.toString(), "--principal", "agent:demo")) {
            session.initialize();

            JsonNode description = session.callTool("cairn_describe", Json.object());

            assertThat(rule(description, "lead-score").get("hasCode").booleanValue()).isFalse();
            assertThat(description.get("origins")).singleElement()
                    .satisfies(origin -> assertThat(origin.get("name").stringValue()).isEqualTo("crm"));
        }
    }

    @Test
    void aRuleThatSeveralTypesNameIsDeclaredForTheirCommonSupertypeAndTheServerStarts() throws Exception {
        Path parties = Files.writeString(directory.resolve("parties.cairn.yaml"), """
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Party: { abstract: true }
                  Person:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                  Organization:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        Store store = CairnAgentServer.createStore(ServerOptions.parse(new String[] {"--principal", "agent:demo",
                "--schema", parties.toString()}));

        assertThat(store.schema().current().origins()).extracting(origin -> origin.name()).containsExactly("crm");
        assertThat(store.declaredRules()).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("lead-score");
            assertThat(rule.appliesTo()).isEqualTo("Party");
        });
        Run run = run("--principal", "agent:demo", "--schema", parties.toString());
        assertThat(run.status()).isZero();
        assertThat(run.err()).contains("lead-score v1 (Party)");
    }

    @Test
    void thePolicyAndThePartyActedForAreOptions() throws Exception {
        try (StdioSession session = new StdioSession("--principal", "agent:demo", "--on-behalf-of", "alice",
                "--policy", "schema=deny,data=approve")) {
            session.initialize();
            JsonNode description = session.callTool("cairn_describe", Json.object());

            JsonNode registration = session.callTool("cairn_register_schema", args("document",
                    "cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\nentityTypes:\n  Lead: {}\n"));
            JsonNode commit = session.callTool("cairn_commit", args("payload",
                    "{\"cairn\": 1, \"principal\": \"agent:demo\", \"writes\": []}"));

            assertThat(description.get("policy").get("schemaRegistrations").stringValue()).isEqualTo("deny");
            assertThat(description.get("policy").get("dataCommits").stringValue()).isEqualTo("requireApproval");
            assertThat(description.get("onBehalfOf").stringValue()).isEqualTo("alice");
            assertThat(registration.get("problems").get(0).get("code").stringValue()).isEqualTo("APPROVAL_DENIED");
            assertThat(commit.get("outcome").stringValue()).isEqualTo("pending");
            assertThat(commit.get("request").get("onBehalfOf").stringValue()).isEqualTo("alice");
        }
    }

    @Test
    void theDocumentsAreRegisteredAtStartAsTheServersPrincipalWithTheirSourceAsTheReason() throws Exception {
        Path crm = Files.writeString(directory.resolve("crm.cairn.yaml"),
                "cairn: 1\norigin: { name: crm, version: \"2.0.0\" }\nentityTypes:\n  Lead: {}\n");
        ServerOptions options = ServerOptions.parse(new String[] {"--principal", "agent:demo", "--on-behalf-of", "alice",
                "--schema", model(), "--schema", crm.toString()});

        Store store = CairnAgentServer.createStore(options);

        assertThat(store.schema().history()).extracting(SchemaTransaction::principal)
                .containsExactly("agent:demo", "agent:demo");
        assertThat(store.schema().history()).extracting(SchemaTransaction::onBehalfOf)
                .allSatisfy(party -> assertThat(party).contains("alice"));
        assertThat(store.schema().history()).extracting(transaction -> transaction.reason().orElseThrow())
                .containsExactly("Registered at server start from reference-model.cairn.yaml",
                        "Registered at server start from crm.cairn.yaml");
        assertThat(store.schema().current().origins()).extracting(origin -> origin.name())
                .containsExactly("crm", "reference-model");
        assertThat(store.declaredRules()).extracting(rule -> rule.name())
                .containsExactly("lei-checksum", "total-ownership-at-most-100");
        assertThat(store.declaredRules()).extracting(rule -> rule.appliesTo())
                .containsExactly("Organization", "Owns");
    }

    @Test
    void aDocumentThatNamesNoRuleDeclaresNone() throws Exception {
        Path crm = Files.writeString(directory.resolve("crm.cairn.yaml"),
                "cairn: 1\norigin: { name: crm, version: \"2.0.0\" }\nentityTypes:\n  Lead: {}\n");

        Store store = CairnAgentServer.createStore(ServerOptions.parse(new String[] {"--principal", "p",
                "--schema", crm.toString()}));

        assertThat(store.declaredRules()).isEmpty();
    }

    @Test
    void aServerWithoutDocumentsStartsWithAnEmptyStore() throws Exception {
        try (StdioSession session = new StdioSession("--principal", "agent:demo")) {
            session.initialize();

            JsonNode description = session.callTool("cairn_describe", Json.object());

            assertThat(description.get("origins")).isEmpty();
            assertThat(description.get("basis").longValue()).isZero();
        }
    }

    // ------------------------------------------------------------------ what the server says at start

    @Test
    void theStartMessageListsTheRulesDeclaredWithoutCodeWithVersionAndType() {
        Run run = run("--schema", model(), "--principal", "agent:demo");

        assertThat(run.status()).isZero();
        assertThat(run.err()).contains("Declared without code (writes to these types are rejected): "
                + "lei-checksum v1 (Organization), total-ownership-at-most-100 v1 (Owns).");
        assertThat(run.err()).contains("Cairn agent server ready: acting as agent:demo");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void theStartMessageSaysNothingAboutRulesWhenNoneAreDeclared() throws IOException {
        Path crm = Files.writeString(directory.resolve("crm.cairn.yaml"),
                "cairn: 1\norigin: { name: crm, version: \"2.0.0\" }\nentityTypes:\n  Lead: {}\n");
        Path leads = Files.writeString(directory.resolve("leads.cairn.yaml"), "cairn: 1\n"
                + "origin: { name: leads, version: \"1.0.0\" }\nentityTypes:\n  Lead:\n    rules:\n"
                + "      - { name: lead-has-source, version: 1 }\n");

        Run withoutRules = run("--principal", "agent:demo", "--schema", crm.toString());
        Run withRuleOnTheClassPath = run("--principal", "agent:demo", "--schema", leads.toString());
        Run withoutDocuments = run("--principal", "agent:demo");

        assertThat(withoutRules.err()).contains("Cairn agent server ready").doesNotContain("Declared without code");
        // lead-has-source is provided on the class path, so it is not declared.
        assertThat(withRuleOnTheClassPath.status()).isZero();
        assertThat(withRuleOnTheClassPath.err()).contains("Cairn agent server ready")
                .doesNotContain("Declared without code");
        assertThat(withoutDocuments.err()).contains("Cairn agent server ready").doesNotContain("Declared without code");
    }

    // ------------------------------------------------------------------ failing to start

    private record Run(int status, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CairnAgentServer.run(args, new ByteArrayInputStream(new byte[0]), out,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void withoutAPrincipalTheServerPrintsTheUsageAndExitsWithStatus2() {
        Run run = run("--schema", model());

        assertThat(run.status()).isEqualTo(2);
        assertThat(run.err()).contains("--principal is required").contains("Usage:");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void anUnknownOptionExitsWithStatus2() {
        Run run = run("--principal", "p", "--port", "80");

        assertThat(run.status()).isEqualTo(2);
        assertThat(run.err()).contains("Unknown option --port.");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void helpPrintsTheUsageOnStandardOutput() {
        Run run = run("--help");

        assertThat(run.status()).isZero();
        assertThat(run.out()).contains("Usage:").contains("--principal").contains("--schema").contains("--policy");
    }

    @Test
    void aSchemaDocumentThatDoesNotExistExitsWithStatus1() {
        Run run = run("--principal", "p", "--schema", directory.resolve("missing.yaml").toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("missing.yaml").contains("cannot be read");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aSchemaDocumentWithProblemsExitsWithStatus1AndSaysWhereTheyAre() throws IOException {
        Path broken = Files.writeString(directory.resolve("broken.yaml"),
                "cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\nentitiTypes:\n  Lead: {}\n");

        Run run = run("--principal", "p", "--schema", broken.toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("broken.yaml").contains("UNKNOWN_KEY").contains("line 3");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aSchemaDocumentThatCannotBeRegisteredExitsWithStatus1() throws IOException {
        Path broken = Files.writeString(directory.resolve("broken.yaml"),
                "cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\nentityTypes:\n  Lead:\n    supertype: Prospect\n");

        Run run = run("--principal", "p", "--schema", broken.toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("cannot be registered").contains("UNKNOWN_REFERENCE").contains("line 4");
    }

    @Test
    void aRuleNameThatIsMalformedIsReportedWithItsLocationAndNotDeclared() throws IOException {
        Path ruled = Files.writeString(directory.resolve("ruled.yaml"), "cairn: 1\n"
                + "origin: { name: crm, version: \"1.0.0\" }\nentityTypes:\n  Lead:\n    rules:\n"
                + "      - { name: Lead_Score, version: 1 }\n");

        Run run = run("--principal", "p", "--schema", ruled.toString());

        assertThat(run.status()).isEqualTo(1);
        assertThat(run.err()).contains("cannot be registered").contains("INVALID_RULE_NAME").contains("line 6");
        assertThat(run.out()).isEmpty();
    }
}
