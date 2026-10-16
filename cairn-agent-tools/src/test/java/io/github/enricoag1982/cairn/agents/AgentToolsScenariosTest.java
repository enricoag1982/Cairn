package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.createOrganization;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payloadOf;
import static io.github.enricoag1982.cairn.agents.AgentFixture.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The scenarios of the {@code agent-tools} spec that the tools themselves answer, one test each. */
class AgentToolsScenariosTest {

    private static final String MARCH_1 = "2026-03-01T00:00:00Z";
    private static final String MARCH_31 = "2026-03-31T00:00:00Z";
    private static final String JUNE_1 = "2026-06-01T00:00:00Z";

    /** The query of RD-6, the total balance by ultimate parent, in the form of the published JSON Schema of queries. */
    private static final String TOTAL_BALANCE_BY_ULTIMATE_PARENT = """
            {
              "from": { "type": "Account", "as": "a" },
              "steps": [
                { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } },
                { "traverse": { "from": "holder", "name": "ownedBy", "maxDepth": 10, "as": "party",
                    "relationship": "ownership",
                    "along": { "compare": { "left": { "attribute": "share", "of": "ownership" }, "op": "gt",
                                            "right": { "value": 50 } } } } },
                { "where": { "not": { "hasLink": { "of": "party", "name": "ownedBy", "relationship": "owns",
                    "along": { "compare": { "left": { "attribute": "share", "of": "owns" }, "op": "gt",
                                            "right": { "value": 50 } } } } } } }
              ],
              "groupBy": [ { "of": "party" } ],
              "aggregates": [ { "function": "sum", "attribute": "balance", "of": "a", "as": "total" } ]
            }
            """;

    private final AgentFixture fixture = new AgentFixture();

    /** The reference model document of the fixture's store, with the optional attribute email added to Person. */
    private static String withEmail(String document) {
        String edited = document.replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n",
                        "      birthDate:\n        type: date\n      email:\n        type: string\n");
        assertThat(edited).isNotEqualTo(document).contains("email:");
        return edited;
    }

    private static List<String> codes(JsonNode problems) {
        List<String> codes = new ArrayList<>();
        problems.forEach(problem -> codes.add(problem.get("code").stringValue()));
        return codes;
    }

    // ------------------------------------------------------------------ describing the store

    @Test
    @DisplayName("Scenario: Reading the schema as documents")
    void readingTheSchemaAsDocuments() {
        JsonNode description = fixture.call("cairn_describe");

        assertThat(description.get("origins")).hasSize(1);
        JsonNode origin = description.get("origins").get(0);
        assertThat(origin.get("name").stringValue()).isEqualTo("reference-model");
        DocumentReadResult read = SchemaDocuments.read(origin.get("document").stringValue());
        assertThat(read).isInstanceOf(DocumentReadResult.Read.class);
        StoredDefinition stored = fixture.store.schema().storedDefinition("reference-model").orElseThrow();
        assertThat(((DocumentReadResult.Read) read).document().origin()).isEqualTo(stored.origin());
        assertThat(((DocumentReadResult.Read) read).document().definition()).isEqualTo(stored.definition());
        assertThat(description.get("rules")).map(rule -> rule.get("name").stringValue())
                .containsExactly("lei-checksum", "total-ownership-at-most-100");
    }

    @Test
    @DisplayName("Scenario: JSON Schema of a type's values")
    void jsonSchemaOfATypesValues() {
        JsonNode schema = fixture.call("cairn_json_schema", args("kind", "values", "type", "Organization"));

        assertThat(schema.get("required")).map(JsonNode::stringValue).containsExactly("name");
        assertThat(schema.get("properties").propertyNames()).contains("name", "lei");
        assertThat(schema.get("properties").get("lei").get("type").stringValue()).isEqualTo("string");
        assertThat(schema.get("properties").get("lei").has("pattern")).isTrue();
    }

    // ------------------------------------------------------------------ schema changes

    @Test
    @DisplayName("Scenario: An agent adds an optional attribute")
    void anAgentAddsAnOptionalAttribute() {
        String document = withEmail(fixture.documentOf("reference-model"));

        JsonNode plan = fixture.call("cairn_plan_schema", args("document", document));
        JsonNode registration = fixture.call("cairn_register_schema",
                args("document", document, "reason", "Contact data"));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("wouldApply");
        assertThat(plan.get("changes")).singleElement().satisfies(change -> {
            assertThat(change.get("path").stringValue()).isEqualTo("Person.email");
            assertThat(change.get("compatible").booleanValue()).isTrue();
            assertThat(change.get("description").stringValue()).isNotBlank();
        });
        assertThat(plan.get("problems")).isEmpty();
        assertThat(registration.get("outcome").stringValue()).isEqualTo("applied");
        assertThat(registration.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        assertThat(registration.has("transactionTime")).isTrue();
        assertThat(fixture.store.schema().current().entityType("Person").orElseThrow().attribute("email")).isPresent();
    }

    @Test
    @DisplayName("Scenario: An agent's breaking change")
    void anAgentsBreakingChange() {
        String document = AgentFixture.documentWithoutBirthDate();

        JsonNode registration = fixture.call("cairn_register_schema", args("document", document));

        assertThat(registration.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(registration.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("BREAKING_CHANGE");
            assertThat(problem.get("path").stringValue()).isEqualTo("Person.birthDate");
            assertThat(problem.get("hint").stringValue()).contains("deprecated");
            assertThat(problem.get("location").get("pointer").stringValue()).isEqualTo("/entityTypes/Person");
            int line = problem.get("location").get("line").intValue();
            assertThat(document.split("\n")[line - 1]).contains("Person:");
        });
        assertThat(fixture.store.schema().history()).hasSize(1);
        assertThat(fixture.store.schema().current().entityType("Person").orElseThrow().attribute("birthDate"))
                .isPresent();
    }

    // ------------------------------------------------------------------ writing data

    @Test
    @DisplayName("Scenario: An agent records an ownership")
    void anAgentRecordsAnOwnership() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        String ownership = payload("{\"relate\": {\"relationshipType\": \"Owns\", \"source\": \"" + acme.value()
                + "\", \"target\": \"" + beta.value() + "\", \"from\": \"" + MARCH_1 + "\", "
                + "\"values\": {\"share\": 30}}}");

        JsonNode commit = fixture.call("cairn_commit", args("payload", ownership));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("committed");
        JsonNode receipt = commit.get("receipt");
        assertThat(receipt.get("principal").stringValue()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(receipt.get("created")).singleElement().satisfies(created -> {
            View view = fixture.store.view(Coordinates.effectiveAt(on(3, 1)));
            assertThat(view.navigate(acme, "Owns")).singleElement().satisfies(link ->
                    assertThat(link.relationship().id().value()).isEqualTo(created.get("relationship").stringValue()));
        });
    }

    @Test
    @DisplayName("Scenario: An agent fixes its own mistake")
    void anAgentFixesItsOwnMistake() {
        String mistaken = payload(createOrganization("ACME").replace("\"name\"", "\"nmae\""));

        JsonNode first = fixture.call("cairn_dry_run", args("payload", mistaken));

        assertThat(first.get("outcome").stringValue()).isEqualTo("rejected");
        JsonNode typo = null;
        for (JsonNode problem : first.get("problems")) {
            if (problem.get("code").stringValue().equals("UNKNOWN_ATTRIBUTE")) {
                typo = problem;
            }
        }
        assertThat(typo).as("the problem with the misspelled attribute: %s", first).isNotNull();
        assertThat(typo.get("hint").stringValue()).contains("name");

        JsonNode second = fixture.call("cairn_dry_run", args("payload", mistaken.replace("nmae", "name")));

        assertThat(second.get("outcome").stringValue()).isEqualTo("wouldCommit");
        assertThat(fixture.store.latestBasis().value()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ reading data

    @Test
    @DisplayName("Scenario: Who owns Beta, as an agent")
    void whoOwnsBetaAsAnAgent() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt ownership = fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
        fixture.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));

        JsonNode owners = fixture.call("cairn_navigate",
                args("id", beta.value(), "name", "ownedBy", "effectiveAt", MARCH_1));

        assertThat(owners.get("effectiveAt").stringValue()).isEqualTo(MARCH_1);
        assertThat(owners.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        assertThat(owners.get("result")).singleElement().satisfies(link -> {
            assertThat(link.get("entity").get("id").stringValue()).isEqualTo(acme.value());
            assertThat(link.get("entity").get("values").get("name").stringValue()).isEqualTo("ACME");
            assertThat(link.get("relationship").get("relationshipType").stringValue()).isEqualTo("Owns");
            assertThat(link.get("relationship").get("values").get("share").decimalValue()).isEqualByComparingTo("55");
        });
    }

    @Test
    @DisplayName("Scenario: Traversing to the end, as an agent")
    void traversingToTheEndAsAnAgent() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt ownership = fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
        fixture.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));
        Receipt withGamma = fixture.replay(new ReferenceStories.Recording(on(5, 3), Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate("Owns", GAMMA, acme, from(2, 1), values("share", 70)).build()));

        JsonNode owners = fixture.call("cairn_traverse",
                args("id", beta.value(), "name", "ownedBy", "effectiveAt", JUNE_1));

        assertThat(owners.has("problems")).isFalse();
        assertThat(owners.get("effectiveAt").stringValue()).isEqualTo(JUNE_1);
        assertThat(owners.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        assertThat(owners.get("result")).extracting(step -> step.get("entity").get("id").stringValue(),
                step -> step.get("entity").get("values").get("name").stringValue(),
                step -> step.get("depth").intValue(),
                step -> step.get("via").get("values").get("share").decimalValue().intValue())
                .containsExactly(tuple(acme.value(), "ACME", 1, 55),
                        tuple(entity(withGamma, GAMMA).value(), "Gamma", 2, 70));
    }

    @Test
    @DisplayName("Scenario: Reading an account by its number, as an agent")
    void readingAnAccountByItsNumberAsAnAgent() {
        Receipt parties = fixture.createParties();
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        EntityId a1 = entity(accounts, ReferenceStories.A1);
        fixture.replay(ReferenceStories.rd5BalancePatch(a1));
        fixture.replay(ReferenceStories.rd5BalanceCorrection(a1));
        ObjectNode arguments = args("effectiveAt", MARCH_31);
        arguments.set("key", Json.parse("{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" } }"));

        JsonNode read = fixture.call("cairn_read_entity", arguments);

        assertThat(read.has("problems")).isFalse();
        assertThat(read.get("effectiveAt").stringValue()).isEqualTo(MARCH_31);
        assertThat(read.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        assertThat(read.get("result").get("id").stringValue()).isEqualTo(a1.value());
        assertThat(read.get("result").get("entityType").stringValue()).isEqualTo("Account");
        assertThat(read.get("result").get("values").get("number").stringValue()).isEqualTo("A-1");
        assertThat(read.get("result").get("values").get("balance").decimalValue()).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("Scenario: An agent totals balances")
    void anAgentTotalsBalances() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(beta, acme));
        Receipt ownership = fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
        EntityId a1 = entity(accounts, ReferenceStories.A1);
        fixture.replay(ReferenceStories.rd5BalancePatch(a1));
        fixture.replay(ReferenceStories.rd5BalanceCorrection(a1));
        fixture.replay(ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA)));

        JsonNode answer = fixture.call("cairn_query", args("query", TOTAL_BALANCE_BY_ULTIMATE_PARENT,
                "effectiveAt", MARCH_31));

        assertThat(answer.has("problems")).isFalse();
        assertThat(answer.get("effectiveAt").stringValue()).isEqualTo(MARCH_31);
        assertThat(answer.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        JsonNode result = answer.get("result");
        assertThat(result.get("columns")).map(JsonNode::stringValue).containsExactly("party", "total");
        assertThat(result.get("rows")).singleElement().satisfies(row -> {
            assertThat(row).hasSize(2);
            assertThat(row.get(0).get("id").stringValue()).isEqualTo(acme.value());
            assertThat(row.get(0).get("entityType").stringValue()).isEqualTo("Organization");
            assertThat(row.get(0).get("values").get("name").stringValue()).isEqualTo("ACME");
            assertThat(row.get(1).decimalValue()).isEqualByComparingTo("3200");
        });
    }

    // ------------------------------------------------------------------ the approval policy

    @Test
    @DisplayName("Scenario: A commit waiting for approval")
    void aCommitWaitingForApproval() {
        AgentFixture approving = new AgentFixture(new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL));

        JsonNode commit = approving.call("cairn_commit", args("payload", payload(createOrganization("ACME"))));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("pending");
        JsonNode request = commit.get("request");
        assertThat(request.get("status").stringValue()).isEqualTo("pending");
        assertThat(request.get("kind").stringValue()).isEqualTo("dataCommit");
        assertThat(request.get("principal").stringValue()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(approving.store.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).isEmpty();
        assertThat(approving.queue.pending()).singleElement()
                .satisfies(pending -> assertThat(pending.id()).isEqualTo(request.get("id").stringValue()));

        approving.queue.approve(request.get("id").stringValue(), "alice");

        List<EntityState> organizations = approving.store.view(Coordinates.effectiveAt(on(2, 1)))
                .entities("Organization");
        assertThat(organizations).singleElement().satisfies(acme ->
                assertThat(acme.values().get("name")).isEqualTo(Value.of("ACME")));
        JsonNode status = approving.call("cairn_approval_status", args("requestId", request.get("id").stringValue()));
        assertThat(status.get("status").stringValue()).isEqualTo("approved");
        assertThat(status.get("decidedBy").stringValue()).isEqualTo("alice");
        assertThat(status.get("outcome").get("outcome").stringValue()).isEqualTo("committed");
        assertThat(status.get("outcome").get("receipt").get("created")).singleElement().satisfies(created ->
                assertThat(created.get("entity").stringValue()).isEqualTo(organizations.getFirst().id().value()));
        assertThat(approving.store.transactions()).singleElement().satisfies(transaction ->
                assertThat(transaction.principal()).isEqualTo(AgentFixture.PRINCIPAL));
    }

    @Test
    @DisplayName("Scenario: An approval request keeps the agent's clearance")
    void anApprovalRequestKeepsTheAgentsClearance() {
        AgentFixture approving = new AgentFixture(Definitions.withClassification(ReferenceModel.definition(), "Account",
                "balance", "finance"), Clearance.none());
        Receipt parties = approving.createParties();
        Receipt accounts = approving.replay(ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        EntityId a1 = entity(accounts, ReferenceStories.A1);
        AgentTools agent = new AgentTools(approving.store, new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.none());
        String patch = payload("{\"patch\": {\"target\": \"" + a1.value() + "\", \"from\": "
                + "\"2026-05-01T00:00:00Z\", \"set\": {\"currency\": \"CHF\"}}}");

        JsonNode commit = Json.parse(agent.commit(Json.render(args("payload", patch))));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("pending");
        JsonNode request = commit.get("request");
        assertThat(request.get("clearance").isArray()).isTrue();
        assertThat(request.get("clearance")).as("the agent's clearance covers no classification").isEmpty();
        approving.queue.approve(request.get("id").stringValue(), "alice");
        ApprovalRequest approved = approving.queue.find(request.get("id").stringValue()).orElseThrow();
        assertThat(approved.clearance()).contains(Clearance.none());
        assertThat(Json.parse(approved.outcome().orElseThrow()).get("outcome").stringValue()).isEqualTo("committed");
        EntityState state = approving.store.view(Coordinates.effectiveAt(on(5, 1))).entity(a1).orElseThrow();
        assertThat(state.values()).containsEntry("currency", Value.of("CHF"));
    }

    @Test
    @DisplayName("Scenario: Denied schema registrations")
    void deniedSchemaRegistrations() {
        AgentFixture denying = new AgentFixture(new AgentPolicy(Decision.DENY, Decision.ALLOW));
        String document = withEmail(denying.documentOf("reference-model"));

        JsonNode registration = denying.call("cairn_register_schema", args("document", document));

        assertThat(registration.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(registration.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("APPROVAL_DENIED");
            assertThat(problem.get("location").get("pointer").stringValue()).isEmpty();
        });
        assertThat(denying.store.schema().history()).hasSize(1);
        assertThat(denying.store.schema().current().entityType("Person").orElseThrow().attribute("email")).isEmpty();
        assertThat(denying.queue.all()).isEmpty();
    }

    // ------------------------------------------------------------------ reading changes

    @Test
    @DisplayName("Scenario: An agent polls for changes")
    void anAgentPollsForChanges() {
        Receipt parties = fixture.createParties();
        ObjectNode none = Json.object();

        JsonNode first = fixture.call("cairn_list_changes", none);
        assertThat(first.get("changes")).as("the registration and RD-1").hasSize(2);
        assertThat(first.get("next").longValue()).isEqualTo(parties.basis().value());
        Receipt employment = fixture.replay(ReferenceStories.rd3Employment(entity(parties, BETA),
                entity(parties, ReferenceStories.ALICE)));

        JsonNode second = fixture.call("cairn_list_changes", Json.object().put("after", first.get("next").longValue()));

        assertThat(second.get("changes")).singleElement().satisfies(change -> {
            assertThat(change.get("basis").longValue()).isEqualTo(employment.basis().value());
            assertThat(change.get("kind").stringValue()).isEqualTo("transaction");
            assertThat(change.get("changed")).singleElement().satisfies(changed -> {
                assertThat(changed.get("relationship").stringValue()).isEqualTo(
                        relationship(employment, ReferenceStories.BETA_EMPLOYS_ALICE).value());
                assertThat(changed.get("type").stringValue()).isEqualTo("Employs");
                assertThat(changed.get("created").booleanValue()).isTrue();
            });
        });
        assertThat(second.get("next").longValue()).isEqualTo(employment.basis().value());
        assertThat(second.get("latestBasis").longValue()).isEqualTo(employment.basis().value());
    }

    @Test
    @DisplayName("Scenario: A limit out of range")
    void aLimitOutOfRange() {
        JsonNode result = fixture.call("cairn_list_changes", Json.object().put("limit", 0));

        assertThat(result.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("INVALID_VALUE");
            assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.limit");
        });
        assertThat(result.has("changes")).isFalse();
    }

    // ------------------------------------------------------------------ the adapter's principal

    @Test
    @DisplayName("Scenario: An agent cannot act as someone else")
    void anAgentCannotActAsSomeoneElse() {
        String asAlice = payloadOf("alice", createOrganization("ACME"));

        JsonNode commit = fixture.call("cairn_commit", args("payload", asAlice));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(commit.get("problems")).singleElement().satisfies(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("PRINCIPAL_MISMATCH");
            assertThat(problem.get("path").stringValue()).isEqualTo("@transaction.principal");
            assertThat(problem.get("location").get("pointer").stringValue()).isEqualTo("/principal");
            assertThat(problem.get("expected").stringValue()).isEqualTo(AgentFixture.PRINCIPAL);
            assertThat(problem.get("found").stringValue()).isEqualTo("alice");
        });
        assertThat(fixture.store.transactions()).isEmpty();
        assertThat(fixture.store.latestBasis().value()).isEqualTo(1);
        assertThat(codes(fixture.call("cairn_dry_run", args("payload", asAlice)).get("problems")))
                .containsExactly("PRINCIPAL_MISMATCH");
    }

    @Test
    void theSchemaTransactionOfARegistrationRecordsThePrincipalAndTheReason() {
        fixture.call("cairn_register_schema",
                args("document", withEmail(fixture.documentOf("reference-model")), "reason", "Contact data"));

        SchemaTransaction change = fixture.store.schema().history().getLast();
        assertThat(change.principal()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(change.reason()).contains("Contact data");
        assertThat(change.onBehalfOf()).isEmpty();
    }
}
