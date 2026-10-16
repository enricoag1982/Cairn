package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.agents.AgentFixture.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools read and write through the clearance they were created with, beyond the scenarios of the
 * {@code attribute-access} spec (ADR-0036, design D5 of {@code add-attribute-access-control}): every tool that reads
 * lists what it withheld, a key or a query that names a withheld attribute is a problem {@code ACCESS_DENIED} in the result
 * and never an error, every commit and dry run carries the clearance, a payload cannot widen it, and nor can a person who
 * approves a request. Account's {@code balance} is {@code finance}, Organization's {@code lei} is {@code identity} and
 * Owns' {@code share} is {@code ownership}.
 */
class AgentClearanceTest {

    private static final String MARCH_31 = "2026-03-31T00:00:00Z";
    private static final String MAY_1 = "2026-05-01T00:00:00Z";
    private static final String ACME_BY_LEI = "{ \"entityType\": \"Organization\", \"key\": "
            + "{ \"lei\": \"5493001KJTIIGC8Y1R12\" } }";
    private static final String NOBODY_BY_LEI = "{ \"entityType\": \"Organization\", \"key\": "
            + "{ \"lei\": \"5493001KJTIIGC8Y1R13\" } }";
    private static final String A1_BY_NUMBER = "{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" } }";

    private static SchemaDefinition model() {
        SchemaDefinition model = Definitions.withClassification(ReferenceModel.definition(), "Account", "balance",
                "finance");
        model = Definitions.withClassification(model, "Organization", "lei", "identity");
        return Definitions.withClassification(model, "Owns", "share", "ownership");
    }

    private final AgentFixture none = new AgentFixture(model(), Clearance.none());
    private EntityId acme;
    private EntityId beta;
    private EntityId a1;
    private RelationshipId owns;

    private void theStories(AgentFixture fixture) {
        Receipt parties = fixture.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        a1 = entity(fixture.replay(ReferenceStories.rd5Accounts(beta, acme)), A1);
        owns = relationship(fixture.replay(ReferenceStories.rd2Ownership(acme, beta)), ACME_OWNS_BETA);
        fixture.replay(ReferenceStories.rd9AcmeLei(acme));
    }

    private static ObjectNode withKey(String key, String... namesAndValues) {
        ObjectNode arguments = args(namesAndValues);
        arguments.set("key", Json.parse(key));
        return arguments;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.stringValue()));
        return names;
    }

    private static JsonNode only(JsonNode result) {
        assertThat(result.get("problems")).hasSize(1);
        return result.get("problems").get(0);
    }

    // ------------------------------------------------------------------ reading

    @Test
    void everyToolThatReadsAStateListsWhatItWithheld() {
        theStories(none);

        JsonNode entity = none.call("cairn_read_entity", args("id", acme.value(), "effectiveAt", MARCH_31));
        JsonNode relationship = none.call("cairn_read_relationship", args("id", owns.value(), "effectiveAt", MARCH_31));
        JsonNode listed = none.call("cairn_list_entities", args("type", "Party", "effectiveAt", MARCH_31));
        JsonNode timeline = none.call("cairn_timeline", args("id", a1.value()));
        JsonNode relationshipTimeline = none.call("cairn_timeline", args("id", owns.value()));
        JsonNode navigated = none.call("cairn_navigate", args("id", acme.value(), "name", "Owns", "effectiveAt",
                MARCH_31));
        JsonNode traversed = none.call("cairn_traverse", args("id", acme.value(), "name", "Owns", "effectiveAt",
                MARCH_31));

        assertThat(names(entity.get("result").get("withheld"))).containsExactly("lei");
        assertThat(entity.get("result").get("values").has("lei")).isFalse();
        assertThat(entity.get("result").get("values").get("name").stringValue()).isEqualTo("ACME");
        assertThat(names(relationship.get("result").get("withheld"))).containsExactly("share");
        assertThat(relationship.get("result").get("values").has("share")).isFalse();
        assertThat(listed.get("result")).filteredOn(state -> state.get("entityType").stringValue().equals("Organization"))
                .hasSize(2).allSatisfy(state -> assertThat(names(state.get("withheld"))).containsExactly("lei"));
        assertThat(listed.get("result")).filteredOn(state -> state.get("entityType").stringValue().equals("Person"))
                .singleElement().satisfies(state -> assertThat(state.has("withheld")).isFalse());
        assertThat(timeline.get("result")).isNotEmpty().allSatisfy(version ->
                assertThat(names(version.get("withheld"))).containsExactly("balance"));
        assertThat(relationshipTimeline.get("result")).isNotEmpty().allSatisfy(version ->
                assertThat(names(version.get("withheld"))).containsExactly("share"));
        assertThat(navigated.get("result")).singleElement().satisfies(link -> {
            assertThat(names(link.get("relationship").get("withheld"))).containsExactly("share");
            assertThat(names(link.get("entity").get("withheld"))).containsExactly("lei");
        });
        assertThat(traversed.get("result")).singleElement().satisfies(step -> {
            assertThat(names(step.get("via").get("withheld"))).containsExactly("share");
            assertThat(names(step.get("entity").get("withheld"))).containsExactly("lei");
        });
    }

    @Test
    void theRowsOfAQueryListWhatTheyWithheld() {
        theStories(none);
        String query = "{\"from\": {\"type\": \"Organization\", \"as\": \"o\"}, \"steps\": [{\"follow\": {\"from\": "
                + "\"o\", \"name\": \"Owns\", \"as\": \"p\", \"relationship\": \"owns\"}}]}";

        JsonNode result = none.call("cairn_query", args("query", query, "effectiveAt", MARCH_31));

        assertThat(result.has("problems")).isFalse();
        JsonNode row = result.get("result").get("rows").get(0);
        assertThat(names(result.get("result").get("columns"))).containsExactly("o", "p", "owns");
        assertThat(names(row.get(0).get("withheld"))).containsExactly("lei");
        assertThat(names(row.get(1).get("withheld"))).containsExactly("lei");
        assertThat(names(row.get(2).get("withheld"))).containsExactly("share");
    }

    @Test
    void aClearanceThatCoversWhatIsClassifiedWithholdsNothing() {
        AgentFixture all = new AgentFixture(model(), Clearance.of("finance", "identity", "ownership"));
        theStories(all);

        JsonNode entity = all.call("cairn_read_entity", args("id", acme.value(), "effectiveAt", MARCH_31));
        JsonNode relationship = all.call("cairn_read_relationship", args("id", owns.value(), "effectiveAt", MARCH_31));

        assertThat(entity.get("result").get("values").get("lei").stringValue()).isEqualTo("5493001KJTIIGC8Y1R12");
        assertThat(entity.get("result").has("withheld")).isFalse();
        assertThat(relationship.get("result").get("values").get("share").decimalValue()).isEqualByComparingTo("60");
        assertThat(relationship.get("result").has("withheld")).isFalse();
    }

    // ------------------------------------------------------------------ keys and queries

    @Test
    void aKeyOfAWithheldAttributeIsAProblemAtTheKeyWhateverTheToolAndWhetherOrNotAnEntityHasTheValue() {
        theStories(none);

        for (String key : List.of(ACME_BY_LEI, NOBODY_BY_LEI)) {
            JsonNode entity = none.call("cairn_read_entity", withKey(key, "effectiveAt", MARCH_31));
            JsonNode navigated = none.call("cairn_navigate", withKey(key, "name", "Owns", "effectiveAt", MARCH_31));
            JsonNode traversed = none.call("cairn_traverse", withKey(key, "name", "Owns", "effectiveAt", MARCH_31));

            for (JsonNode result : List.of(entity, navigated, traversed)) {
                JsonNode problem = only(result);
                assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
                assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.key");
                assertThat(problem.get("message").stringValue()).contains("lei", "identity");
                assertThat(problem.get("expected").stringValue()).contains("identity");
                assertThat(problem.get("found").stringValue()).isEqualTo("a clearance that covers no classification");
                assertThat(result.has("result")).isFalse();
            }
        }
    }

    @Test
    void aKeyOfAnAttributeTheClearanceCoversOrOfAnAttributeWithoutAClassificationIsFound() {
        AgentFixture identity = new AgentFixture(model(), Clearance.of("identity"));
        theStories(identity);

        JsonNode byLei = identity.call("cairn_read_entity", withKey(ACME_BY_LEI, "effectiveAt", MARCH_31));
        JsonNode byNumber = none.call("cairn_read_entity", withKey(A1_BY_NUMBER, "effectiveAt", MARCH_31));
        JsonNode nobody = identity.call("cairn_read_entity", withKey(NOBODY_BY_LEI, "effectiveAt", MARCH_31));

        assertThat(byLei.get("result").get("id").stringValue()).isEqualTo(acme.value());
        assertThat(byLei.get("result").get("values").get("lei").stringValue()).isEqualTo("5493001KJTIIGC8Y1R12");
        assertThat(nobody.get("result").isNull()).isTrue();
        assertThat(byNumber.has("problems")).as("nothing is recorded in the store of this fixture").isFalse();
    }

    @Test
    void aKeyThatTheSchemaDoesNotAllowIsStillAnInvalidKey() {
        theStories(none);
        String unknown = "{ \"entityType\": \"Organization\", \"key\": { \"nothing\": \"x\" } }";

        JsonNode result = none.call("cairn_read_entity", withKey(unknown, "effectiveAt", MARCH_31));

        assertThat(only(result).get("code").stringValue()).isNotEqualTo("ACCESS_DENIED");
    }

    @Test
    void aQueryThatNamesAWithheldAttributeIsAProblemInTheQueryEvenInAnotherPlace() {
        theStories(none);
        String sum = "{\"from\": {\"type\": \"Account\", \"as\": \"a\"}, \"aggregates\": [{\"function\": \"sum\", "
                + "\"attribute\": \"balance\", \"of\": \"a\", \"as\": \"total\"}]}";
        String order = "{\"from\": {\"type\": \"Account\", \"as\": \"a\"}, \"order\": [{\"attribute\": "
                + "\"balance\", \"of\": \"a\"}]}";

        JsonNode summed = none.call("cairn_query", args("query", sum, "effectiveAt", MARCH_31));
        JsonNode ordered = none.call("cairn_query", args("query", order, "effectiveAt", MARCH_31));

        assertThat(only(summed).get("code").stringValue()).isEqualTo("ACCESS_DENIED");
        assertThat(only(summed).get("path").stringValue()).isEqualTo("@query.aggregates.0");
        assertThat(only(ordered).get("code").stringValue()).isEqualTo("ACCESS_DENIED");
        assertThat(only(ordered).get("path").stringValue()).isEqualTo("@query.order.0");
    }

    // ------------------------------------------------------------------ describing

    @Test
    void theDescriptionReportsTheClearanceAndTheClassificationsOfTheSchema() {
        AgentFixture cleared = new AgentFixture(model(), Clearance.of("identity", "finance"));

        JsonNode withNone = none.call("cairn_describe");
        JsonNode withTwo = cleared.call("cairn_describe");

        assertThat(withNone.get("clearance").isArray()).isTrue();
        assertThat(withNone.get("clearance")).isEmpty();
        assertThat(names(withTwo.get("clearance"))).containsExactly("identity", "finance");
        String document = withNone.get("origins").get(0).get("document").stringValue();
        assertThat(document).contains("classification: finance").contains("classification: identity")
                .contains("classification: ownership");
        assertThat(SchemaDocuments.read(document)).isNotNull();
    }

    @Test
    void theToolsAreDescribedAsWithholdingAndRefusing() {
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            if (List.of("cairn_read_entity", "cairn_read_relationship", "cairn_list_entities", "cairn_timeline",
                    "cairn_navigate", "cairn_traverse", "cairn_query").contains(tool.name())) {
                assertThat(tool.description()).as(tool.name()).contains("withheld");
            }
        }
        assertThat(description("cairn_query")).contains("ACCESS_DENIED");
        assertThat(description("cairn_read_entity")).contains("ACCESS_DENIED");
        assertThat(description("cairn_commit")).contains("clearance").contains("ACCESS_DENIED");
        assertThat(description("cairn_dry_run")).contains("clearance").contains("ACCESS_DENIED");
        assertThat(description("cairn_describe")).contains("clearance").contains("classification");
    }

    private static String description(String tool) {
        return AgentTools.toolDescriptions().stream().filter(candidate -> candidate.name().equals(tool))
                .findFirst().orElseThrow().description();
    }

    // ------------------------------------------------------------------ writing

    private static String createAccount(String balance) {
        return "{\"create\": {\"temporaryId\": \"a9\", \"entityType\": \"Account\", \"from\": \"2026-01-01T00:00:00Z\", "
                + "\"values\": {\"number\": \"A-9\", \"currency\": \"EUR\", \"balance\": " + balance + "}}}";
    }

    private static String createHolder() {
        return "{\"create\": {\"temporaryId\": \"holder\", \"entityType\": \"Organization\", \"from\": "
                + "\"2026-01-01T00:00:00Z\", \"values\": {\"name\": \"Delta\"}}}";
    }

    private static String relateHolder() {
        return "{\"relate\": {\"relationshipType\": \"HeldBy\", \"source\": \"$a9\", \"target\": \"$holder\", "
                + "\"from\": \"2026-01-01T00:00:00Z\"}}";
    }

    @Test
    void aDryRunAndACommitCarryTheClearanceOfTheTools() {
        theStories(none);
        String payload = payload(createHolder(), createAccount("500"), relateHolder());

        JsonNode dry = none.call("cairn_dry_run", args("payload", payload));
        JsonNode committed = none.call("cairn_commit", args("payload", payload));

        for (JsonNode result : List.of(dry, committed)) {
            assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
            JsonNode problem = only(result);
            assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
            assertThat(problem.get("path").stringValue()).isEqualTo("$a9.balance");
            assertThat(problem.get("location").get("pointer").stringValue())
                    .isEqualTo("/writes/1/create/values/balance");
        }
        assertThat(none.store.view(Coordinates.effectiveAt(ReferenceStories.on(3, 31))).entities("Account"))
                .hasSize(2);
    }

    @Test
    void aClearanceThatCoversTheClassificationLetsTheAgentWriteAndRead() {
        AgentFixture finance = new AgentFixture(model(), Clearance.of("finance"));
        theStories(finance);
        String payload = payload(createHolder(), createAccount("500"), relateHolder());

        JsonNode dry = finance.call("cairn_dry_run", args("payload", payload));
        JsonNode committed = finance.call("cairn_commit", args("payload", payload));

        assertThat(dry.get("outcome").stringValue()).isEqualTo("wouldCommit");
        assertThat(committed.get("outcome").stringValue()).isEqualTo("committed");
        JsonNode accounts = finance.call("cairn_list_entities", args("type", "Account", "effectiveAt", MARCH_31));
        assertThat(accounts.get("result")).hasSize(3).allSatisfy(state ->
                assertThat(state.get("values").has("balance")).isTrue());
    }

    @Test
    void aPayloadCannotGiveOrWidenAClearance() {
        theStories(none);
        String widening = "{\"cairn\": 1, \"principal\": \"" + AgentFixture.PRINCIPAL + "\", \"clearance\": [\"finance\"], "
                + "\"writes\": [" + createHolder() + ", " + createAccount("500") + ", " + relateHolder() + "]}";

        JsonNode dry = none.call("cairn_dry_run", args("payload", widening));
        JsonNode committed = none.call("cairn_commit", args("payload", widening));

        for (JsonNode result : List.of(dry, committed)) {
            assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
            assertThat(result.get("problems")).extracting(problem -> problem.get("code").stringValue())
                    .containsExactly("UNKNOWN_KEY");
        }
        assertThat(none.store.view(Coordinates.effectiveAt(ReferenceStories.on(3, 31))).entities("Account"))
                .hasSize(2);
    }

    @Test
    void aPayloadThatNamesAnEntityByAKeyOfAWithheldAttributeIsRejectedAtTheReferenceWhetherOrNotItExists() {
        theStories(none);

        for (String key : List.of(ACME_BY_LEI, NOBODY_BY_LEI)) {
            String patch = "{\"patch\": {\"target\": " + key + ", \"from\": \"2026-03-15T00:00:00Z\", \"set\": "
                    + "{\"name\": \"ACME Holdings\"}}}";

            JsonNode dry = none.call("cairn_dry_run", args("payload", payload(patch)));
            JsonNode committed = none.call("cairn_commit", args("payload", payload(patch)));

            for (JsonNode result : List.of(dry, committed)) {
                JsonNode problem = only(result);
                assertThat(problem.get("code").stringValue()).isEqualTo("ACCESS_DENIED");
                assertThat(problem.get("path").stringValue()).isEqualTo("@write.0");
            }
        }
    }

    // ------------------------------------------------------------------ approval

    @Test
    void anApprovedRequestIsCommittedWithTheClearanceOfTheAgentThatAskedAndTheRequestShowsIt() {
        AgentFixture approving = new AgentFixture(model(), Clearance.none());
        AgentTools finance = new AgentTools(approving.store, new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.of("finance"));
        AgentTools nothing = new AgentTools(approving.store, new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.none());
        theStories(approving);
        String payload = payload(createHolder(), createAccount("500"), relateHolder());

        JsonNode refused = Json.parse(nothing.commit(Json.render(args("payload", payload))));
        JsonNode pending = Json.parse(finance.commit(Json.render(args("payload", payload))));

        assertThat(refused.get("outcome").stringValue()).as("it would be rejected now, so no person is asked")
                .isEqualTo("rejected");
        assertThat(only(refused).get("code").stringValue()).isEqualTo("ACCESS_DENIED");
        assertThat(pending.get("outcome").stringValue()).isEqualTo("pending");
        assertThat(names(pending.get("request").get("clearance"))).containsExactly("finance");
        String id = pending.get("request").get("id").stringValue();
        ApprovalRequest approved = approving.queue.approve(id, "alice");
        assertThat(approved.clearance()).hasValue(Clearance.of("finance"));
        assertThat(Json.parse(approved.outcome().orElseThrow()).get("outcome").stringValue()).isEqualTo("committed");
        assertThat(approving.store.view(Coordinates.effectiveAt(ReferenceStories.on(3, 31))).entities("Account"))
                .hasSize(3);
    }

    @Test
    void aSchemaRegistrationRequestHasNoClearance() {
        AgentFixture approving = new AgentFixture(model(), Clearance.none());
        AgentTools tools = new AgentTools(approving.store, new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.ALLOW),
                approving.queue, AgentFixture.PRINCIPAL, Optional.empty(), Clearance.of("finance"));
        String document = approving.documentOf("reference-model").replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("      birthDate:\n        type: date\n",
                        "      birthDate:\n        type: date\n      email:\n        type: string\n");

        JsonNode pending = Json.parse(tools.registerSchema(Json.render(args("document", document))));

        assertThat(pending.get("outcome").stringValue()).isEqualTo("pending");
        assertThat(pending.get("request").has("clearance")).isFalse();
        assertThat(approving.queue.pending().getFirst().clearance()).isEmpty();
    }

    // ------------------------------------------------------------------ creating the tools

    @Test
    void toolsCreatedWithoutAClearanceHaveNone() {
        theStories(none);
        AgentTools tools = new AgentTools(none.store, AgentPolicy.defaults(), none.queue, AgentFixture.PRINCIPAL,
                Optional.empty());

        JsonNode read = Json.parse(tools.readEntity(Json.render(args("id", a1.value(), "effectiveAt", MARCH_31))));

        assertThat(names(read.get("result").get("withheld"))).containsExactly("balance");
        assertThat(Json.parse(tools.describe("{}")).get("clearance")).isEmpty();
    }

    @Test
    void theClearanceOfTheToolsMustNotBeNull() {
        assertThatThrownBy(() -> new AgentTools(none.store, AgentPolicy.defaults(), none.queue, AgentFixture.PRINCIPAL,
                Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }
}
