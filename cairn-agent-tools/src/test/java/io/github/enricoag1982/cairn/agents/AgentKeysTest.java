package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unique keys in the tools that read from an entity (ADR-0026, design D6 of {@code add-unique-key-references}): the
 * three tools take {@code id} or {@code key}, exactly one, find a key at the call's effective time, read a key that names
 * nothing as nothing, and report an invalid key at {@code @arguments.key}. A payload with keys is dry-run and committed
 * like any other.
 */
class AgentKeysTest {

    private static final String MARCH_1 = "2026-03-01T00:00:00Z";
    private static final String MARCH_31 = "2026-03-31T00:00:00Z";
    private static final String A1 = "{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" } }";
    private static final String ACME_LEI = "{ \"entityType\": \"Organization\", \"key\": "
            + "{ \"lei\": \"5493001KJTIIGC8Y1R12\" } }";

    private final AgentFixture fixture = new AgentFixture();
    private EntityId a1;
    private EntityId acme;
    private EntityId beta;

    @BeforeEach
    void theStories() {
        Receipt parties = fixture.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        Receipt accounts = fixture.replay(ReferenceStories.rd5Accounts(beta, acme));
        a1 = entity(accounts, ReferenceStories.A1);
        fixture.replay(ReferenceStories.rd5BalancePatch(a1));
        fixture.replay(ReferenceStories.rd5BalanceCorrection(a1));
        fixture.replay(ReferenceStories.rd9AcmeLei(acme));
        fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
    }

    private static ObjectNode withKey(String key, String... namesAndValues) {
        ObjectNode arguments = args(namesAndValues);
        arguments.set("key", Json.parse(key));
        return arguments;
    }

    private static List<String> codes(JsonNode result) {
        List<String> codes = new ArrayList<>();
        result.get("problems").forEach(problem -> codes.add(problem.get("code").stringValue()));
        return codes;
    }

    private static JsonNode only(JsonNode result) {
        assertThat(result.get("problems")).hasSize(1);
        return result.get("problems").get(0);
    }

    // ------------------------------------------------------------------ reading an entity

    @Test
    void anEntityIsReadByItsKeyAsByItsId() {
        JsonNode byKey = fixture.call("cairn_read_entity", withKey(A1, "effectiveAt", MARCH_31));
        JsonNode byId = fixture.call("cairn_read_entity", args("id", a1.value(), "effectiveAt", MARCH_31));

        assertThat(byKey).isEqualTo(byId);
        assertThat(byKey.get("result").get("id").stringValue()).isEqualTo(a1.value());
    }

    @Test
    void aKeyIsFoundAtTheEffectiveTimeOfTheCallAndReadsAsNothingWhereNoEntityHasTheValue() {
        ObjectNode before = withKey(A1, "effectiveAt", "2025-12-31T00:00:00Z");
        ObjectNode unknown = withKey("{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-9\" } }",
                "effectiveAt", MARCH_31);

        JsonNode beforeTheAccountExists = fixture.call("cairn_read_entity", before);
        JsonNode noSuchAccount = fixture.call("cairn_read_entity", unknown);

        for (JsonNode nothing : List.of(beforeTheAccountExists, noSuchAccount)) {
            assertThat(nothing.has("problems")).isFalse();
            assertThat(nothing.get("result").isNull()).isTrue();
            assertThat(nothing.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        }
        assertThat(beforeTheAccountExists.get("effectiveAt").stringValue()).isEqualTo("2025-12-31T00:00:00Z");
    }

    @Test
    void aKeyIsReadAsAsOfABasisAsWell() {
        long created = fixture.store.transactions().getFirst().basis().value();
        ObjectNode arguments = withKey(A1, "effectiveAt", MARCH_31);
        arguments.set("knownAt", Json.parse("{\"basis\": " + created + "}"));

        JsonNode read = fixture.call("cairn_read_entity", arguments);

        assertThat(read.get("basis").longValue()).isEqualTo(created);
        assertThat(read.get("result").isNull()).as("the account was recorded later").isTrue();
    }

    @Test
    void theNamesAndAliasesOfTheKeyAreComparedWithoutRegardToCase() {
        JsonNode read = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"account\", \"key\": { \"NUMBER\": \"A-1\" } }", "effectiveAt", MARCH_31));

        assertThat(read.get("result").get("id").stringValue()).isEqualTo(a1.value());
    }

    // ------------------------------------------------------------------ navigating and traversing

    @Test
    void navigatingFromAKeyIsNavigatingFromTheEntity() {
        JsonNode byKey = fixture.call("cairn_navigate", withKey(ACME_LEI, "name", "Owns", "effectiveAt", MARCH_31));
        JsonNode byId = fixture.call("cairn_navigate", args("id", acme.value(), "name", "Owns",
                "effectiveAt", MARCH_31));

        assertThat(byKey).isEqualTo(byId);
        assertThat(byKey.get("result")).singleElement().satisfies(link ->
                assertThat(link.get("entity").get("id").stringValue()).isEqualTo(beta.value()));
    }

    @Test
    void traversingFromAKeyIsTraversingFromTheEntity() {
        JsonNode byKey = fixture.call("cairn_traverse", withKey(ACME_LEI, "name", "Owns", "effectiveAt", MARCH_31)
                .put("maxDepth", 3));
        JsonNode byId = fixture.call("cairn_traverse", args("id", acme.value(), "name", "Owns",
                "effectiveAt", MARCH_31).put("maxDepth", 3));

        assertThat(byKey).isEqualTo(byId);
        assertThat(byKey.get("result")).singleElement().satisfies(step ->
                assertThat(step.get("entity").get("id").stringValue()).isEqualTo(beta.value()));
    }

    @Test
    void traversingFromAKeyWithoutAMaximumDepthGoesToTheEnd() {
        JsonNode byKey = fixture.call("cairn_traverse", withKey(ACME_LEI, "name", "Owns", "effectiveAt", MARCH_31));
        JsonNode byId = fixture.call("cairn_traverse", args("id", acme.value(), "name", "Owns",
                "effectiveAt", MARCH_31));

        assertThat(byKey.has("problems")).isFalse();
        assertThat(byKey).isEqualTo(byId);
        assertThat(byKey).isEqualTo(fixture.call("cairn_traverse", withKey(ACME_LEI, "name", "Owns",
                "effectiveAt", MARCH_31).put("maxDepth", 3)));
        assertThat(byKey.get("result")).singleElement().satisfies(step ->
                assertThat(step.get("entity").get("id").stringValue()).isEqualTo(beta.value()));
    }

    @Test
    void aKeyThatNamesNothingLeadsNowhereWithTheCoordinatesOfTheRead() {
        ObjectNode nobody = withKey("{ \"entityType\": \"Organization\", \"key\": { \"lei\": \"none\" } }",
                "name", "Owns", "effectiveAt", MARCH_31);

        JsonNode navigated = fixture.call("cairn_navigate", nobody);
        JsonNode traversed = fixture.call("cairn_traverse", nobody.deepCopy().put("maxDepth", 2));

        for (JsonNode nothing : List.of(navigated, traversed)) {
            assertThat(nothing.has("problems")).isFalse();
            assertThat(nothing.get("result").isArray()).isTrue();
            assertThat(nothing.get("result")).isEmpty();
            assertThat(nothing.get("effectiveAt").stringValue()).isEqualTo(MARCH_31);
        }
    }

    @Test
    void anUnknownNameIsAProblemAtTheNameWhetherTheEntityIsNamedByAnIdOrByAKey() {
        JsonNode byKey = fixture.call("cairn_navigate", withKey(ACME_LEI, "name", "Ownz", "effectiveAt", MARCH_31));
        JsonNode traversed = fixture.call("cairn_traverse", withKey(ACME_LEI, "name", "Ownz", "effectiveAt", MARCH_31)
                .put("maxDepth", 1));

        assertThat(only(byKey).get("code").stringValue()).isEqualTo("UNKNOWN_RELATIONSHIP_TYPE");
        assertThat(only(byKey).get("path").stringValue()).isEqualTo("@arguments.name");
        assertThat(only(traversed).get("path").stringValue()).isEqualTo("@arguments.name");
    }

    // ------------------------------------------------------------------ the entity is named by one of two arguments

    /** The arguments that a tool needs besides the entity: the name and the depth, where it takes them. */
    private static ObjectNode base(String tool, ObjectNode arguments) {
        if (!tool.equals("cairn_read_entity")) {
            arguments.put("name", "Owns");
        }
        if (tool.equals("cairn_traverse")) {
            arguments.put("maxDepth", 1);
        }
        return arguments;
    }

    @Test
    void aCallWithNeitherOrBothOfIdAndKeyIsAProblemAtTheId() {
        for (String tool : List.of("cairn_read_entity", "cairn_navigate", "cairn_traverse")) {
            JsonNode noneGiven = fixture.call(tool, base(tool, args("effectiveAt", MARCH_31)));
            JsonNode bothGiven = fixture.call(tool, base(tool, withKey(A1, "id", a1.value(), "effectiveAt", MARCH_31)));

            assertThat(codes(noneGiven)).as(tool).containsExactly("MISSING_KEY");
            assertThat(only(noneGiven).get("path").stringValue()).as(tool).isEqualTo("@arguments.id");
            assertThat(codes(bothGiven)).as(tool).containsExactly("INVALID_VALUE");
            assertThat(only(bothGiven).get("path").stringValue()).as(tool).isEqualTo("@arguments.id");
            assertThat(only(bothGiven).get("hint").stringValue()).contains("'id'", "'key'");
        }
    }

    @Test
    void aWrongKindOfIdIsNotAlsoReportedAsAMissingOne() {
        ObjectNode arguments = args("effectiveAt", MARCH_31);
        arguments.put("id", 5);

        JsonNode result = fixture.call("cairn_read_entity", arguments);

        assertThat(codes(result)).containsExactly("INVALID_VALUE");
        assertThat(only(result).get("path").stringValue()).isEqualTo("@arguments.id");
    }

    // ------------------------------------------------------------------ invalid keys

    @Test
    void aKeyThatIsNoObjectIsAProblemAtTheKey() {
        ObjectNode arguments = args("effectiveAt", MARCH_31);
        arguments.put("key", "A-1");

        JsonNode result = fixture.call("cairn_read_entity", arguments);

        assertThat(codes(result)).containsExactly("INVALID_VALUE");
        assertThat(only(result).get("path").stringValue()).isEqualTo("@arguments.key");
        assertThat(only(result).get("expected").stringValue()).contains("unique key");
    }

    @Test
    void aKeyWithTwoAttributesOrNoneOrAnotherShapeIsAProblemAtTheKeyWithoutALocationInTheArguments() {
        for (String key : List.of(
                "{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\", \"currency\": \"EUR\" } }",
                "{ \"entityType\": \"Account\", \"key\": {} }",
                "{ \"entityType\": \"Account\" }",
                "{ \"key\": { \"number\": \"A-1\" } }",
                "{ \"entityType\": \"Account\", \"key\": { \"number\": \"A-1\" }, \"other\": 1 }",
                "{ \"entityType\": 5, \"key\": { \"number\": \"A-1\" } }",
                "{ \"entityType\": \"Account\", \"key\": { \"number\": [1] } }")) {
            JsonNode result = fixture.call("cairn_read_entity", withKey(key, "effectiveAt", MARCH_31));

            assertThat(result.has("problems")).as(key).isTrue();
            result.get("problems").forEach(problem -> {
                assertThat(problem.get("path").stringValue()).as(key).isEqualTo("@arguments.key");
                assertThat(problem.has("location")).as("the arguments are not the text the agent wrote: " + key)
                        .isFalse();
            });
        }
    }

    @Test
    void anAttributeThatIsNotUniqueIsInvalidAndTheExpectedValueNamesTheUniqueOnes() {
        JsonNode result = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"Account\", \"key\": { \"currency\": \"EUR\" } }", "effectiveAt", MARCH_31));

        assertThat(codes(result)).containsExactly("INVALID_UNIQUE_KEY");
        JsonNode problem = only(result);
        assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.key");
        assertThat(problem.get("expected").stringValue()).isEqualTo("one of number");
        assertThat(problem.get("message").stringValue()).contains("currency", "not unique");
        assertThat(problem.get("found").stringValue()).isEqualTo("Account(currency: EUR)");
    }

    @Test
    void anUnknownTypeOrAttributeOrAValueThatDoesNotFitIsInvalidWithAHint() {
        JsonNode type = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"Acount\", \"key\": { \"number\": \"A-1\" } }", "effectiveAt", MARCH_31));
        JsonNode attribute = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"Account\", \"key\": { \"numbr\": \"A-1\" } }", "effectiveAt", MARCH_31));
        JsonNode value = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"Account\", \"key\": { \"number\": 1 } }", "effectiveAt", MARCH_31));
        JsonNode relationship = fixture.call("cairn_read_entity", withKey(
                "{ \"entityType\": \"Owns\", \"key\": { \"share\": 1 } }", "effectiveAt", MARCH_31));

        assertThat(codes(type)).containsExactly("INVALID_UNIQUE_KEY");
        assertThat(only(type).get("hint").stringValue()).isEqualTo("Did you mean 'Account'?");
        assertThat(codes(attribute)).containsExactly("INVALID_UNIQUE_KEY");
        assertThat(only(attribute).get("expected").stringValue()).isEqualTo("one of number");
        assertThat(codes(value)).containsExactly("INVALID_UNIQUE_KEY");
        assertThat(only(value).get("message").stringValue()).contains("does not fit");
        assertThat(codes(relationship)).as("keys name entities only").containsExactly("INVALID_UNIQUE_KEY");
        for (JsonNode result : List.of(type, attribute, value, relationship)) {
            assertThat(only(result).get("path").stringValue()).isEqualTo("@arguments.key");
        }
    }

    @Test
    void anInvalidKeyIsAProblemFromNavigateAndTraverseToo() {
        ObjectNode currency = withKey("{ \"entityType\": \"Account\", \"key\": { \"currency\": \"EUR\" } }",
                "name", "HeldBy", "effectiveAt", MARCH_31);

        JsonNode navigated = fixture.call("cairn_navigate", currency);
        JsonNode traversed = fixture.call("cairn_traverse", currency.deepCopy().put("maxDepth", 1));

        for (JsonNode result : List.of(navigated, traversed)) {
            assertThat(codes(result)).containsExactly("INVALID_UNIQUE_KEY");
            assertThat(only(result).get("path").stringValue()).isEqualTo("@arguments.key");
        }
    }

    // ------------------------------------------------------------------ the catalog

    @Test
    void theInputSchemasOfTheThreeToolsDescribeTheKeyAndNeitherRequiresTheId() {
        for (String name : List.of("cairn_read_entity", "cairn_navigate", "cairn_traverse")) {
            AgentTools.ToolDescription tool = AgentTools.toolDescriptions().stream()
                    .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            JsonNode schema = Json.parse(tool.inputSchema());

            assertThat(schema.get("required")).as(name).map(JsonNode::stringValue).doesNotContain("id", "key")
                    .contains("effectiveAt");
            assertThat(schema.get("properties").propertyNames()).as(name).contains("id", "key");
            JsonNode key = schema.get("properties").get("key");
            assertThat(key.get("type").stringValue()).isEqualTo("object");
            assertThat(key.get("required")).map(JsonNode::stringValue).containsExactly("entityType", "key");
            assertThat(key.get("additionalProperties").booleanValue()).isFalse();
            assertThat(key.get("properties").get("key").get("minProperties").intValue()).isEqualTo(1);
            assertThat(key.get("properties").get("key").get("maxProperties").intValue()).isEqualTo(1);
            assertThat(key.get("description").stringValue()).contains("unique", "id");
            assertThat(schema.get("properties").get("id").get("description").stringValue()).contains("key");
            assertThat(tool.description()).as(name).contains("unique key", "exactly one");
            assertThat(schema.has("oneOf")).as("clients reject a oneOf at the top of an input schema").isFalse();
        }
    }

    @Test
    void theNewProblemCodesAreExplained() {
        for (String code : List.of("INVALID_UNIQUE_KEY", "UNIQUE_KEY_NOT_FOUND")) {
            JsonNode explanation = fixture.call("cairn_explain_problem", args("code", code));

            assertThat(explanation.get("code").stringValue()).isEqualTo(code);
            assertThat(explanation.get("explanation").stringValue()).isNotBlank();
        }
        assertThat(fixture.call("cairn_describe").get("problemCodes")).extracting(node -> node.get("code").stringValue())
                .contains("INVALID_UNIQUE_KEY", "UNIQUE_KEY_NOT_FOUND");
    }

    // ------------------------------------------------------------------ payloads with keys

    private static String patchByKey(String key, String set) {
        return "{\"patch\": {\"target\": " + key + ", \"from\": \"2026-03-15T00:00:00Z\", \"set\": " + set + "}}";
    }

    @Test
    void aPayloadThatNamesAnAccountByItsNumberIsDryRunAndCommitted() {
        String payload = payload(patchByKey(A1, "{\"balance\": 1800}"));

        JsonNode dryRun = fixture.call("cairn_dry_run", args("payload", payload));
        JsonNode commit = fixture.call("cairn_commit", args("payload", payload));

        assertThat(dryRun.get("outcome").stringValue()).isEqualTo("wouldCommit");
        assertThat(commit.get("outcome").stringValue()).isEqualTo("committed");
        assertThat(commit.get("receipt").get("keys")).as("the receipt says which entity the key named")
                .singleElement().satisfies(resolution -> {
                    assertThat(resolution.get("write").intValue()).isZero();
                    assertThat(resolution.get("key").get("entityType").stringValue()).isEqualTo("Account");
                    assertThat(resolution.get("key").get("key").get("number").stringValue()).isEqualTo("A-1");
                    assertThat(resolution.get("entity").stringValue()).isEqualTo(a1.value());
                });
        JsonNode read = fixture.call("cairn_read_entity", withKey(A1, "effectiveAt", MARCH_31));
        assertThat(read.get("result").get("values").get("balance").decimalValue()).isEqualByComparingTo("1800");
    }

    @Test
    void aKeyThatNamesNothingIsRejectedAtItsWriteAndAProblemAboutAnEntityNamedByAKeyIsLocatedAtTheWrite() {
        String missing = patchByKey("{\"entityType\": \"Account\", \"key\": {\"number\": \"A-9\"}}",
                "{\"balance\": 1800}");
        String badValue = patchByKey(A1, "{\"currency\": \"eur\"}");

        JsonNode notFound = fixture.call("cairn_dry_run", args("payload", payload(patchByKey(A1,
                "{\"balance\": 1}"), missing)));
        JsonNode located = fixture.call("cairn_dry_run", args("payload", payload(badValue)));

        assertThat(codes(notFound)).containsExactly("UNIQUE_KEY_NOT_FOUND");
        assertThat(only(notFound).get("path").stringValue()).isEqualTo("@write.1");
        assertThat(only(notFound).get("location").get("pointer").stringValue()).isEqualTo("/writes/1");
        assertThat(codes(located)).containsExactly("VALUE_PATTERN_MISMATCH");
        assertThat(only(located).get("path").stringValue()).isEqualTo(a1.value() + ".currency");
        assertThat(only(located).get("location").get("pointer").stringValue()).isEqualTo("/writes/0/patch/set/currency");
    }

    @Test
    void aPayloadWithAKeyOfTheWrongShapeHasProblemsLocatedInTheText() {
        String payload = payload(patchByKey(
                "{\"entityType\": \"Account\", \"key\": {\"number\": \"A-1\", \"currency\": \"EUR\"}}",
                "{\"balance\": 1800}"));

        JsonNode result = fixture.call("cairn_dry_run", args("payload", payload));

        assertThat(result.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(codes(result)).containsExactly("INVALID_VALUE");
        assertThat(only(result).get("location").get("pointer").stringValue()).isEqualTo("/writes/0/patch/target/key");
    }
}
