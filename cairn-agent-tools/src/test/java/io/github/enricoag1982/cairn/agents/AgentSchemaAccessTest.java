package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an agent may change in the schema, and what it may read of the requests it and other agents made (ADR-0036, design D5
 * of {@code add-attribute-access-control}): a plan, a registration and an approved request are checked against the
 * clearance of the agent, which may not change the classification of what it may not read, nor add a check or a derived
 * attribute over it; the schema is not changed by a refusal; a request keeps the clearance of the agent that made it, also
 * when a person approves it; and {@code cairn_approval_status} answers only to the adapter that made the request. Account's
 * {@code balance} is {@code finance} in these tests only.
 */
class AgentSchemaAccessTest {

    private static final String MARCH_31 = "2026-03-31T00:00:00Z";

    private static SchemaDefinition plain() {
        return ReferenceModel.definition();
    }

    private static SchemaDefinition finance() {
        return Definitions.withClassification(plain(), "Account", "balance", "finance");
    }

    private static String document(String version, SchemaDefinition definition) {
        return SchemaDocuments.writeYaml(ReferenceModel.origin(version), definition);
    }

    private static ObjectNode schemaArgs(String document) {
        return args("document", document);
    }

    private static List<String> codes(JsonNode result) {
        List<String> codes = new ArrayList<>();
        result.get("problems").forEach(problem -> codes.add(problem.get("code").stringValue()));
        return codes;
    }

    private static Optional<String> balanceClassification(AgentFixture fixture) {
        return fixture.store.schema().current().entityType("Account").orElseThrow().attribute("balance").orElseThrow()
                .classification();
    }

    // ------------------------------------------------------------------ plans and registrations

    @Test
    void aPlanSaysThatTheRegistrationWouldBeRefused() {
        AgentFixture fixture = new AgentFixture(finance(), Clearance.none());

        JsonNode plan = fixture.call("cairn_plan_schema", schemaArgs(document("1.1.0", plain())));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(plan.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("Account.balance");
        });
        assertThat(plan.get("changes")).as("the changes are listed too").isNotEmpty();
        assertThat(balanceClassification(fixture)).hasValue("finance");
    }

    @Test
    void anAgentWhoseClearanceCoversTheClassificationMayChangeIt() {
        AgentFixture fixture = new AgentFixture(finance(), Clearance.of("finance"));

        JsonNode plan = fixture.call("cairn_plan_schema", schemaArgs(document("1.1.0", plain())));
        JsonNode registered = fixture.call("cairn_register_schema", schemaArgs(document("1.1.0", plain())));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("wouldApply");
        assertThat(registered.get("outcome").stringValue()).isEqualTo("applied");
        assertThat(balanceClassification(fixture)).isEmpty();
    }

    @Test
    void anAgentWithoutAClearanceCannotClassifyEither() {
        AgentFixture fixture = new AgentFixture(plain(), Clearance.none());

        JsonNode result = fixture.call("cairn_register_schema", schemaArgs(document("1.1.0", finance())));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(codes(result)).containsExactly("ACCESS_DENIED");
        assertThat(balanceClassification(fixture)).isEmpty();
    }

    @Test
    void anAgentMayChangeTheSchemaWhereNoClassificationIsInvolved() {
        AgentFixture fixture = new AgentFixture(finance(), Clearance.none());
        SchemaDefinition withEmail = Definitions.changeEntityType(finance(), "Person", person ->
                Definitions.withAttributes(person, append(person.attributes(),
                        AttributeDefinition.builder("email", ValueType.STRING).build())));

        JsonNode result = fixture.call("cairn_register_schema", schemaArgs(document("1.1.0", withEmail)));

        assertThat(result.get("outcome").stringValue()).isEqualTo("applied");
        assertThat(balanceClassification(fixture)).hasValue("finance");
    }

    private static <T> List<T> append(List<T> items, T added) {
        List<T> all = new ArrayList<>(items);
        all.add(added);
        return all;
    }

    // ------------------------------------------------------------------ requests that wait for a person

    @Test
    void aRequestThatWouldDeclassifyIsRefusedAtOnceAndNotPutToAPerson() {
        AgentFixture approving = new AgentFixture(finance(), Clearance.none());
        AgentTools tools = new AgentTools(approving.store, new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.ALLOW),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.none());

        JsonNode result = Json.parse(tools.registerSchema(Json.render(schemaArgs(document("1.1.0", plain())))));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(codes(result)).containsExactly("ACCESS_DENIED");
        assertThat(approving.queue.pending()).isEmpty();
    }

    @Test
    void aPersonWhoApprovesARequestAppliesItWithTheClearanceOfTheAgentThatAskedForIt() {
        AgentFixture approving = new AgentFixture(plain(), Clearance.none());
        AgentTools tools = new AgentTools(approving.store, new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.ALLOW),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.none());
        // The request is harmless when it is made: it leaves the balance as it is, without a classification.
        SchemaDefinition withEmail = Definitions.changeEntityType(plain(), "Person", person ->
                Definitions.withAttributes(person, append(person.attributes(),
                        AttributeDefinition.builder("email", ValueType.STRING).build())));
        JsonNode pending = Json.parse(tools.registerSchema(Json.render(schemaArgs(document("1.1.0", withEmail)))));
        assertThat(pending.get("outcome").stringValue()).isEqualTo("pending");
        assertThat(pending.get("request").get("clearance")).isEmpty();
        // Before a person decides, another registration classifies the balance, so that approving the request would
        // declassify it.
        assertThat(approving.store.schema().register(ReferenceModel.origin("1.0.5"), finance(),
                Submission.by("importer"))).isInstanceOf(RegistrationResult.Applied.class);
        String id = pending.get("request").get("id").stringValue();

        ApprovalRequest approved = approving.queue.approve(id, "hr-director");

        assertThat(approved.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
        JsonNode outcome = Json.parse(approved.outcome().orElseThrow());
        assertThat(outcome.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(codes(outcome)).containsExactly("ACCESS_DENIED");
        assertThat(balanceClassification(approving)).as("approving did not widen the clearance").hasValue("finance");
    }

    @Test
    void aRequestOfAnAgentWithTheClearanceMayBeApprovedToChangeWhatItCovers() {
        AgentFixture approving = new AgentFixture(finance(), Clearance.of("finance"));
        AgentTools tools = new AgentTools(approving.store, new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.ALLOW),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.of("finance"));
        JsonNode pending = Json.parse(tools.registerSchema(Json.render(schemaArgs(document("1.1.0", plain())))));
        assertThat(pending.get("outcome").stringValue()).isEqualTo("pending");

        ApprovalRequest approved = approving.queue.approve(pending.get("request").get("id").stringValue(), "hr-director");

        assertThat(Json.parse(approved.outcome().orElseThrow()).get("outcome").stringValue()).isEqualTo("applied");
        assertThat(balanceClassification(approving)).isEmpty();
    }

    // ------------------------------------------------------------------ the status of a request

    @Test
    void theStatusOfARequestIsGivenOnlyToTheAdapterThatMadeIt() {
        AgentFixture shared = new AgentFixture(plain(), Clearance.none());
        AgentPolicy requireApproval = new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL);
        AgentTools maker = new AgentTools(shared.store, requireApproval, shared.queue, "agent:one", Optional.empty(),
                Clearance.of("finance"));
        AgentTools sameClearanceOtherPrincipal = new AgentTools(shared.store, requireApproval, shared.queue, "agent:two",
                Optional.empty(), Clearance.of("finance"));
        AgentTools samePrincipalNoClearance = new AgentTools(shared.store, requireApproval, shared.queue, "agent:one",
                Optional.empty(), Clearance.none());
        AgentTools samePrincipalOtherClearance = new AgentTools(shared.store, requireApproval, shared.queue,
                "agent:one", Optional.empty(), Clearance.of("identity"));
        JsonNode pending = Json.parse(maker.commit(Json.render(args("payload",
                "{\"cairn\": 1, \"principal\": \"agent:one\", \"writes\": []}"))));
        String id = pending.get("request").get("id").stringValue();
        String arguments = Json.render(args("requestId", id));

        JsonNode own = Json.parse(maker.approvalStatus(arguments));
        JsonNode unknown = Json.parse(maker.approvalStatus(Json.render(args("requestId", "no-such-request"))));
        List<JsonNode> others = List.of(Json.parse(sameClearanceOtherPrincipal.approvalStatus(arguments)),
                Json.parse(samePrincipalNoClearance.approvalStatus(arguments)),
                Json.parse(samePrincipalOtherClearance.approvalStatus(arguments)));

        assertThat(own.get("status").stringValue()).isEqualTo("pending");
        assertThat(own.get("id").stringValue()).isEqualTo(id);
        assertThat(unknown.get("problems").get(0).get("message").stringValue()).contains("no-such-request");
        for (JsonNode other : others) {
            assertThat(other.has("status")).isFalse();
            assertThat(other.get("problems")).hasSize(1);
            assertThat(other.get("problems").get(0).get("code").stringValue())
                    .isEqualTo(unknown.get("problems").get(0).get("code").stringValue());
            assertThat(other.get("problems").get(0).get("message").stringValue())
                    .isEqualTo("There is no approval request with the ID " + id + ".");
            assertThat(other.toString()).doesNotContain("agent:one").doesNotContain("\"payload\"");
        }
    }

    // ------------------------------------------------------------------ reading at an older basis

    @Test
    void aKeyOfAnAttributeClassifiedSinceIsRefusedAtAnOlderBasisToo() {
        AgentFixture fixture = new AgentFixture(plain(), Clearance.none());
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        fixture.replay(ReferenceStories.rd9AcmeLei(acme));
        long before = fixture.store.latestBasis().value();
        assertThat(fixture.store.schema().register(ReferenceModel.origin("1.1.0"),
                Definitions.withClassification(plain(), "Organization", "lei", "identity"),
                Submission.by("importer"))).isInstanceOf(RegistrationResult.Applied.class);
        ObjectNode arguments = args("effectiveAt", MARCH_31);
        arguments.set("key", Json.parse("{\"entityType\": \"Organization\", \"key\": {\"lei\": \"" + LEI + "\"}}"));
        arguments.putObject("knownAt").put("basis", before);
        ObjectNode absent = args("effectiveAt", MARCH_31);
        absent.set("key", Json.parse("{\"entityType\": \"Organization\", \"key\": {\"lei\": \"5493001KJTIIGC8Y1R13\"}}"));
        absent.putObject("knownAt").put("basis", before);

        JsonNode read = fixture.call("cairn_read_entity", arguments);
        JsonNode readAbsent = fixture.call("cairn_read_entity", absent);

        for (JsonNode result : List.of(read, readAbsent)) {
            assertThat(result.has("result")).isFalse();
            assertThat(result.get("problems")).singleElement().satisfies(problem -> {
                assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
                assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.key");
                assertThat(problem.get("message").stringValue()).contains("lei").contains("identity");
            });
        }
    }

    @Test
    void aValueClassifiedSinceIsWithheldFromTheReadsOfAnOlderBasisToo() {
        AgentFixture fixture = new AgentFixture(plain(), Clearance.none());
        Receipt parties = fixture.createParties();
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(entity(parties, ReferenceStories.BETA),
                entity(parties, ACME)));
        EntityId a1 = entity(accounts, ReferenceStories.A1);
        long before = fixture.store.latestBasis().value();
        fixture.store.schema().register(ReferenceModel.origin("1.1.0"), finance(), Submission.by("importer"));
        ObjectNode arguments = args("id", a1.value(), "effectiveAt", MARCH_31);
        arguments.putObject("knownAt").put("basis", before);

        JsonNode read = fixture.call("cairn_read_entity", arguments);

        assertThat(read.get("result").get("values").has("balance")).isFalse();
        assertThat(read.get("result").get("withheld")).map(JsonNode::stringValue).containsExactly("balance");
    }
}
