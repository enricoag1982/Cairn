package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.createOrganization;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.agents.ApprovalRequest.Kind;
import io.github.enricoag1982.cairn.agents.ApprovalRequest.Status;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The approval policy and the queue of requests that wait for a person (design D4 of {@code add-agent-tools}). */
class ApprovalQueueTest {

    private static final AgentPolicy APPROVE_ALL = new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.REQUIRE_APPROVAL);
    private static final String LEI = ReferenceStories.LEI;

    private final AgentFixture fixture = new AgentFixture(APPROVE_ALL);

    private static String withEmail(String document) {
        return document.replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n",
                        "      birthDate:\n        type: date\n      email:\n        type: string\n");
    }

    private String requestSchemaChange(String reason) {
        JsonNode result = fixture.call("cairn_register_schema",
                args("document", withEmail(fixture.documentOf("reference-model")), "reason", reason));
        assertThat(result.get("outcome").stringValue()).isEqualTo("pending");
        return result.get("request").get("id").stringValue();
    }

    private String requestCommit(String payload) {
        JsonNode result = fixture.call("cairn_commit", args("payload", payload));
        assertThat(result.get("outcome").stringValue()).as(result.toString()).isEqualTo("pending");
        return result.get("request").get("id").stringValue();
    }

    private static String organizationWithLei(String name) {
        return "{\"create\": {\"entityType\": \"Organization\", \"from\": \"2026-01-01T00:00:00Z\", \"values\": "
                + "{\"name\": \"" + name + "\", \"lei\": \"" + LEI + "\"}}}";
    }

    // ------------------------------------------------------------------ waiting

    @Test
    void aRequestThatNeedsApprovalWaitsInTheQueueWithWhoAskedAndWhen() {
        String id = requestCommit(payload(createOrganization("ACME")));

        assertThat(fixture.queue.pending()).singleElement().satisfies(request -> {
            assertThat(request.id()).isEqualTo(id);
            assertThat(request.kind()).isEqualTo(Kind.DATA_COMMIT);
            assertThat(request.principal()).isEqualTo(AgentFixture.PRINCIPAL);
            assertThat(request.submittedAt()).isEqualTo(fixture.clock.instant());
            assertThat(request.content()).isEqualTo(payload(createOrganization("ACME")));
            assertThat(request.status()).isEqualTo(Status.PENDING);
            assertThat(request.outcome()).isEmpty();
            assertThat(request.decidedBy()).isEmpty();
            assertThat(request.decidedAt()).isEmpty();
        });
        assertThat(fixture.queue.find(id)).isPresent();
        assertThat(fixture.store.transactions()).isEmpty();
    }

    @Test
    void aPendingRegistrationShowsTheReasonItWasMadeFor() {
        String id = requestSchemaChange("Contact data");

        ApprovalRequest request = fixture.queue.find(id).orElseThrow();

        assertThat(request.reason()).contains("Contact data");
        assertThat(request.kind()).isEqualTo(Kind.SCHEMA_REGISTRATION);
        JsonNode status = fixture.call("cairn_approval_status", args("requestId", id));
        assertThat(status.get("reason").stringValue()).isEqualTo("Contact data");
        assertThat(status.get("status").stringValue()).isEqualTo("pending");
    }

    @Test
    void aRegistrationWithoutAReasonHasNone() {
        JsonNode result = fixture.call("cairn_register_schema",
                args("document", withEmail(fixture.documentOf("reference-model"))));

        assertThat(result.get("outcome").stringValue()).isEqualTo("pending");
        assertThat(result.get("request").has("reason")).isFalse();
        assertThat(fixture.queue.pending()).singleElement().satisfies(request ->
                assertThat(request.reason()).isEmpty());
    }

    @Test
    void aPendingCommitShowsTheReasonOfItsPayload() {
        String withReason = payload(createOrganization("A")).replace("\"writes\"",
                "\"reason\": \"Onboarding ACME\", \"writes\"");

        String id = requestCommit(withReason);
        String withoutReason = requestCommit(payload(createOrganization("B")));

        assertThat(fixture.queue.find(id).orElseThrow().reason()).contains("Onboarding ACME");
        assertThat(fixture.call("cairn_approval_status", args("requestId", id)).get("reason").stringValue())
                .isEqualTo("Onboarding ACME");
        assertThat(fixture.queue.find(withoutReason).orElseThrow().reason()).isEmpty();
        assertThat(fixture.call("cairn_approval_status", args("requestId", withoutReason)).has("reason")).isFalse();
    }

    @Test
    void theReasonStaysOnTheRequestOnceItIsDecidedAndOnTheTransaction() {
        String id = requestSchemaChange("Contact data");
        String commit = requestCommit(payload(createOrganization("A")).replace("\"writes\"",
                "\"reason\": \"Onboarding ACME\", \"writes\""));

        ApprovalRequest approved = fixture.queue.approve(id, "alice");
        ApprovalRequest rejected = fixture.queue.reject(commit, "alice", "Not now.");

        assertThat(approved.reason()).contains("Contact data");
        assertThat(rejected.reason()).contains("Onboarding ACME");
        assertThat(fixture.call("cairn_approval_status", args("requestId", commit)).get("reason").stringValue())
                .isEqualTo("Onboarding ACME");
        assertThat(fixture.store.schema().history().getLast().reason()).contains("Contact data");
    }

    @Test
    void requestIdsAreUuidV7AndRequestsAreListedInTheOrderOfSubmission() {
        String first = requestCommit(payload(createOrganization("A")));
        String second = requestCommit(payload(createOrganization("B")));

        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(second).isGreaterThan(first);
        assertThat(fixture.queue.pending()).map(ApprovalRequest::id).containsExactly(first, second);
    }

    @Test
    void aPendingRequestReportsItsStatusToTheAgent() {
        String id = requestCommit(payload(createOrganization("ACME")));

        JsonNode status = fixture.call("cairn_approval_status", args("requestId", id));

        assertThat(status.get("id").stringValue()).isEqualTo(id);
        assertThat(status.get("status").stringValue()).isEqualTo("pending");
        assertThat(status.has("outcome")).isFalse();
        assertThat(status.has("decidedBy")).isFalse();
    }

    @Test
    void aRequestThatIsRejectedAtOnceIsNotQueued() {
        JsonNode broken = fixture.call("cairn_commit", args("payload", payload(createOrganization("ACME")
                .replace("\"name\"", "\"nmae\""))));
        JsonNode breaking = fixture.call("cairn_register_schema",
                args("document", AgentFixture.documentWithoutBirthDate()));

        assertThat(broken.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(breaking.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(breaking.get("problems").get(0).get("code").stringValue()).isEqualTo("BREAKING_CHANGE");
        assertThat(fixture.queue.all()).isEmpty();
    }

    @Test
    void aRegistrationThatChangesNothingIsNotQueued() {
        JsonNode result = fixture.call("cairn_register_schema", args("document", fixture.documentOf("reference-model")));

        assertThat(result.get("outcome").stringValue()).isEqualTo("unchanged");
        assertThat(fixture.queue.all()).isEmpty();
    }

    // ------------------------------------------------------------------ approving

    @Test
    void approvingACommitAppliesItThenAndRecordsWhoApprovedIt() {
        String id = requestCommit(payload(createOrganization("ACME")));
        fixture.clock.moveTo(Instant.parse("2026-01-06T10:00:00Z"));

        ApprovalRequest approved = fixture.queue.approve(id, "alice");

        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        assertThat(approved.decidedBy()).contains("alice");
        assertThat(approved.decidedAt()).contains(Instant.parse("2026-01-06T10:00:00Z"));
        assertThat(fixture.queue.pending()).isEmpty();
        assertThat(fixture.queue.find(id)).contains(approved);
        TransactionRecord record = fixture.store.transactions().getFirst();
        assertThat(record.principal()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(record.transactionTime()).isEqualTo(Instant.parse("2026-01-06T10:00:00Z"));
        JsonNode outcome = Json.parse(approved.outcome().orElseThrow());
        assertThat(outcome.get("outcome").stringValue()).isEqualTo("committed");
        assertThat(outcome.get("receipt").get("transactionTime").stringValue()).isEqualTo("2026-01-06T10:00:00Z");
    }

    @Test
    void approvingARegistrationRecordsTheAgentAsThePrincipalAndKeepsItsReason() {
        String id = requestSchemaChange("Contact data");

        ApprovalRequest approved = fixture.queue.approve(id, "alice");

        assertThat(approved.kind()).isEqualTo(Kind.SCHEMA_REGISTRATION);
        assertThat(Json.parse(approved.outcome().orElseThrow()).get("outcome").stringValue()).isEqualTo("applied");
        SchemaTransaction change = fixture.store.schema().history().getLast();
        assertThat(change.principal()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(change.reason()).contains("Contact data");
        assertThat(fixture.store.schema().current().entityType("Person").orElseThrow().attribute("email")).isPresent();
    }

    @Test
    void aRequestThatIsInvalidWhenItIsApprovedIsApprovedAllTheSameAndItsOutcomeHoldsTheProblems() {
        String first = requestCommit(payload(organizationWithLei("A")));
        String second = requestCommit(payload(organizationWithLei("B")));
        fixture.queue.approve(first, "alice");

        ApprovalRequest approved = fixture.queue.approve(second, "alice");

        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        JsonNode outcome = Json.parse(approved.outcome().orElseThrow());
        assertThat(outcome.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(outcome.get("problems").get(0).get("code").stringValue()).isEqualTo("UNIQUE_VALUE_TAKEN");
        assertThat(fixture.store.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(1);
        assertThat(fixture.call("cairn_approval_status", args("requestId", second)).get("outcome").get("outcome")
                .stringValue()).isEqualTo("rejected");
    }

    @Test
    void aPayloadThatNamesNoPartyActedForGetsTheOneOfTheTools() {
        AgentFixture acting = new AgentFixture();
        AgentTools onBehalf = new AgentTools(acting.store, AgentPolicy.defaults(), acting.queue, AgentFixture.PRINCIPAL,
                Optional.of("alice"));
        String own = payload(createOrganization("A")).replace("\"writes\"", "\"onBehalfOf\": \"bob\", \"writes\"");

        JsonNode withoutParty = Json.parse(onBehalf.commit(Json.render(args("payload",
                payload(createOrganization("B"))))));
        JsonNode withParty = Json.parse(onBehalf.commit(Json.render(args("payload", own))));

        assertThat(withoutParty.get("receipt").get("onBehalfOf").stringValue()).isEqualTo("alice");
        assertThat(withParty.get("receipt").get("onBehalfOf").stringValue()).isEqualTo("bob");
        assertThat(acting.store.transactions()).extracting(TransactionRecord::onBehalfOf)
                .containsExactly(Optional.of("alice"), Optional.of("bob"));
    }

    @Test
    void aRegistrationRecordsThePartyTheToolsActFor() {
        AgentFixture acting = new AgentFixture();
        AgentTools onBehalf = new AgentTools(acting.store, AgentPolicy.defaults(), acting.queue, AgentFixture.PRINCIPAL,
                Optional.of("alice"));

        onBehalf.registerSchema(Json.render(args("document", withEmail(acting.documentOf("reference-model")))));

        assertThat(acting.store.schema().history().getLast().onBehalfOf()).contains("alice");
    }

    @Test
    void approvingARequestOfAnAdapterThatActsForSomeoneAppliesItForThem() {
        AgentFixture acting = new AgentFixture();
        AgentTools onBehalf = new AgentTools(acting.store, new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL),
                acting.queue, AgentFixture.PRINCIPAL, Optional.of("alice"));
        JsonNode pending = Json.parse(onBehalf.commit(Json.render(args("payload", payload(createOrganization("A"))))));

        acting.queue.approve(pending.get("request").get("id").stringValue(), "carol");

        assertThat(pending.get("request").get("onBehalfOf").stringValue()).isEqualTo("alice");
        assertThat(acting.store.transactions()).singleElement().satisfies(record -> {
            assertThat(record.principal()).isEqualTo(AgentFixture.PRINCIPAL);
            assertThat(record.onBehalfOf()).contains("alice");
        });
    }

    // ------------------------------------------------------------------ rejecting

    @Test
    void rejectingARequestAppliesNothingAndTellsTheAgentWhy() {
        String id = requestCommit(payload(createOrganization("ACME")));

        ApprovalRequest rejected = fixture.queue.reject(id, "alice", "ACME is not a customer yet.");

        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.decidedBy()).contains("alice");
        assertThat(fixture.queue.pending()).isEmpty();
        assertThat(fixture.store.transactions()).isEmpty();
        JsonNode status = fixture.call("cairn_approval_status", args("requestId", id));
        assertThat(status.get("status").stringValue()).isEqualTo("rejected");
        assertThat(status.get("outcome").get("reason").stringValue()).isEqualTo("ACME is not a customer yet.");
    }

    // ------------------------------------------------------------------ misuse

    @Test
    void anUnknownRequestCannotBeDecided() {
        assertThatThrownBy(() -> fixture.queue.approve("nope", "alice")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> fixture.queue.reject("nope", "alice", "no")).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.queue.find("nope")).isEmpty();
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        String approved = requestCommit(payload(createOrganization("A")));
        String rejected = requestCommit(payload(createOrganization("B")));
        fixture.queue.approve(approved, "alice");
        fixture.queue.reject(rejected, "alice", "No.");

        assertThatThrownBy(() -> fixture.queue.approve(approved, "bob")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved");
        assertThatThrownBy(() -> fixture.queue.reject(approved, "bob", "No.")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.queue.approve(rejected, "bob")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
        assertThat(fixture.store.transactions()).hasSize(1);
    }

    @Test
    void theApproverMustBeNamed() {
        String id = requestCommit(payload(createOrganization("A")));

        assertThatThrownBy(() -> fixture.queue.approve(id, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.queue.approve(id, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fixture.queue.reject(id, "", "No.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.queue.reject(id, "alice", null)).isInstanceOf(NullPointerException.class);
        assertThat(fixture.queue.pending()).hasSize(1);
    }

    @Test
    void aQueueBelongsToTheStoreOfItsTools() {
        AgentFixture other = new AgentFixture();

        assertThatThrownBy(() -> new AgentTools(fixture.store, APPROVE_ALL, other.queue, "agent:x", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRecordOfARequestIsConsistent() {
        Instant now = Instant.parse("2026-01-05T09:00:00Z");

        assertThatThrownBy(() -> new ApprovalRequest("id", Kind.DATA_COMMIT, "p", Optional.empty(), Optional.empty(), now, "c",
                Status.PENDING, Optional.empty(), Optional.of("alice"), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalRequest("id", Kind.DATA_COMMIT, "p", Optional.empty(), Optional.empty(), now, "c",
                Status.APPROVED, Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalRequest("id", Kind.DATA_COMMIT, "p", Optional.empty(), Optional.empty(), now, "c",
                Status.PENDING, Optional.of("{}"), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalRequest(null, Kind.DATA_COMMIT, "p", Optional.empty(), Optional.empty(), now, "c",
                Status.PENDING, Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theDefaultPolicyAllowsBothAndTheDecisionsAreRequired() {
        assertThat(AgentPolicy.defaults()).isEqualTo(new AgentPolicy(Decision.ALLOW, Decision.ALLOW));
        assertThatThrownBy(() -> new AgentPolicy(null, Decision.ALLOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentPolicy(Decision.ALLOW, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDeniedCommitIsRejectedWithAProblemLocatedAtThePayloadAndNothingIsQueued() {
        AgentFixture denying = new AgentFixture(new AgentPolicy(Decision.ALLOW, Decision.DENY));

        JsonNode result = denying.call("cairn_commit", args("payload", payload(createOrganization("ACME"))));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(result.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("APPROVAL_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("@transaction");
            assertThat(problem.get("location").get("pointer").stringValue()).isEmpty();
        });
        assertThat(denying.queue.all()).isEmpty();
        assertThat(denying.store.transactions()).isEmpty();
    }

    @Test
    void aDryRunAndAPlanAreNotSubjectToThePolicy() {
        AgentFixture denying = new AgentFixture(new AgentPolicy(Decision.DENY, Decision.DENY));

        JsonNode dryRun = denying.call("cairn_dry_run", args("payload", payload(createOrganization("ACME"))));
        JsonNode plan = denying.call("cairn_plan_schema",
                args("document", withEmail(denying.documentOf("reference-model"))));

        assertThat(dryRun.get("outcome").stringValue()).isEqualTo("wouldCommit");
        assertThat(plan.get("outcome").stringValue()).isEqualTo("wouldApply");
    }
}
