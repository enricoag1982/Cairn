package io.github.enricoag1982.cairn.agents;

import static io.github.enricoag1982.cairn.agents.AgentFixture.args;
import static io.github.enricoag1982.cairn.agents.AgentFixture.createOrganization;
import static io.github.enricoag1982.cairn.agents.AgentFixture.entity;
import static io.github.enricoag1982.cairn.agents.AgentFixture.payload;
import static io.github.enricoag1982.cairn.agents.AgentFixture.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.Instants;
import io.github.enricoag1982.cairn.documents.JsonSchemas;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The tools beyond the scenarios: the other inputs, the other outputs, and wrong input (design D2 and D3). */
class AgentToolsTest {

    private static final String MARCH_1 = "2026-03-01T00:00:00Z";

    private final AgentFixture fixture = new AgentFixture();

    private static List<String> codes(JsonNode node) {
        List<String> codes = new ArrayList<>();
        node.get("problems").forEach(problem -> codes.add(problem.get("code").stringValue()));
        return codes;
    }

    private static JsonNode problem(JsonNode node, String code) {
        for (JsonNode problem : node.get("problems")) {
            if (problem.get("code").stringValue().equals(code)) {
                return problem;
            }
        }
        throw new AssertionError("No problem " + code + " in " + node);
    }

    // ------------------------------------------------------------------ the catalog

    @Test
    void everyToolHasADescriptionAndAnInputSchemaThatIsValidJson() {
        assertThat(AgentTools.toolDescriptions()).hasSize(16).allSatisfy(tool -> {
            assertThat(tool.name()).startsWith("cairn_");
            assertThat(tool.description()).isNotBlank();
            JsonNode schema = Json.parse(tool.inputSchema());
            assertThat(schema.get("type").stringValue()).isEqualTo("object");
            assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
            assertThat(schema.get("required").isArray()).isTrue();
        });
        assertThat(AgentTools.toolDescriptions()).map(AgentTools.ToolDescription::name).doesNotHaveDuplicates();
    }

    @Test
    void theInputSchemaOfAToolNamesItsRequiredArguments() {
        JsonNode traverse = null;
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            if (tool.name().equals("cairn_traverse")) {
                traverse = Json.parse(tool.inputSchema());
            }
        }

        assertThat(traverse).isNotNull();
        assertThat(traverse.get("required")).as("the start is named by id or by key, so neither is required, and "
                + "without a maximum depth the traversal goes to the end")
                .map(JsonNode::stringValue).containsExactly("name", "effectiveAt");
        assertThat(traverse.get("properties").propertyNames()).containsExactlyInAnyOrder("id", "key", "name",
                "maxDepth", "effectiveAt", "knownAt");
        assertThat(traverse.get("properties").get("maxDepth").get("description").stringValue()).contains("to the end");
    }

    @Test
    void theDescriptionsOfTheTraversalAndOfTheQueryToolMentionWhatTheyDoWithoutADepthAndFromGivenIds() {
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            if (tool.name().equals("cairn_traverse")) {
                assertThat(tool.description()).contains("to the end if you give none");
            }
            if (tool.name().equals("cairn_query")) {
                assertThat(tool.description()).contains("ids").contains("an empty list gives no rows");
            }
        }
    }

    @Test
    void everyToolAnswersWithAJsonObjectWhateverTheArguments() {
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            for (String arguments : new String[] {null, "", "  ", "{}", "[]", "null", "42", "\"text\"", "{", "{\"a\":",
                    "{\"unknown\": 1}", "{\"document\": 1, \"payload\": [], \"id\": {}, \"effectiveAt\": false}",
                    "{\"document\": \"\", \"payload\": \"\", \"id\": \"\", \"type\": \"\", \"code\": \"\", \"kind\": \"\"}"}) {
                String result = fixture.tools.call(tool.name(), arguments);

                assertThat(Json.parse(result).isObject()).as("%s with %s: %s", tool.name(), arguments, result).isTrue();
            }
        }
    }

    @Test
    void anUnknownToolIsAProgrammingError() {
        assertThatThrownBy(() -> fixture.tools.call("cairn_nothing", "{}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cairn_nothing");
    }

    @Test
    void theToolsNeedAPrincipal() {
        assertThatThrownBy(() -> new AgentTools(fixture.store, AgentPolicy.defaults(), fixture.queue, " ",
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentTools(fixture.store, AgentPolicy.defaults(), fixture.queue, null,
                Optional.empty())).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ describing

    @Test
    void theDescriptionHoldsTheBasisThePolicyThePrincipalAndEveryProblemCode() {
        JsonNode description = fixture.call("cairn_describe");

        assertThat(description.get("basis").longValue()).isEqualTo(1);
        assertThat(description.get("principal").stringValue()).isEqualTo(AgentFixture.PRINCIPAL);
        assertThat(description.has("onBehalfOf")).isFalse();
        assertThat(description.get("policy").get("schemaRegistrations").stringValue()).isEqualTo("allow");
        assertThat(description.get("policy").get("dataCommits").stringValue()).isEqualTo("allow");
        assertThat(description.get("problemCodes")).hasSize(ProblemCode.values().length);
        assertThat(description.get("problemCodes")).anySatisfy(code -> {
            assertThat(code.get("code").stringValue()).isEqualTo("PRINCIPAL_MISMATCH");
            assertThat(code.get("explanation").stringValue()).isEqualTo(ProblemCode.PRINCIPAL_MISMATCH.explanation());
        });
    }

    @Test
    void theDescriptionOfARuleHasItsTypeItsCodesAndWhetherTheStoreHasItsCode() {
        JsonNode rule = fixture.call("cairn_describe").get("rules").get(0);

        assertThat(rule.get("name").stringValue()).isEqualTo("lei-checksum");
        assertThat(rule.get("version").intValue()).isEqualTo(1);
        assertThat(rule.get("description").stringValue()).contains("check digits");
        assertThat(rule.get("appliesTo").stringValue()).isEqualTo("Organization");
        assertThat(rule.get("codes")).singleElement().satisfies(code -> {
            assertThat(code.get("code").stringValue()).isEqualTo("lei-checksum/invalid-check-digits");
            assertThat(code.get("explanation").stringValue()).isNotBlank();
        });
        assertThat(rule.get("hasCode").booleanValue()).isTrue();
    }

    @Test
    void aRuleTheStoreDeclaresWithoutCodeHasNoCode() {
        AgentFixture declared = new AgentFixture(AgentPolicy.defaults(), true, false);

        JsonNode rules = declared.call("cairn_describe").get("rules");

        assertThat(rules).hasSize(2).allSatisfy(rule -> assertThat(rule.get("hasCode").booleanValue()).isFalse());
        JsonNode commit = declared.call("cairn_commit", args("payload", payload(createOrganization("ACME"))));
        assertThat(codes(commit)).containsExactly("RULE_UNAVAILABLE");
    }

    @Test
    void anEmptyStoreHasNoOrigins() {
        AgentFixture empty = new AgentFixture(AgentPolicy.defaults(), false);

        JsonNode description = empty.call("cairn_describe");

        assertThat(description.get("origins")).isEmpty();
        assertThat(description.get("basis").longValue()).isZero();
        assertThat(description.get("rules")).as("the store has the rules, whatever its schema").hasSize(2);
    }

    @Test
    void theToolsReportThePartyTheyActFor() {
        AgentTools onBehalf = new AgentTools(fixture.store, AgentPolicy.defaults(), fixture.queue,
                AgentFixture.PRINCIPAL, Optional.of("alice"));

        assertThat(Json.parse(onBehalf.describe("{}")).get("onBehalfOf").stringValue()).isEqualTo("alice");
    }

    @Test
    void theDescriptionOfEveryOriginReadsBackToItsStoredDefinition() {
        String other = "{\"cairn\": 1, \"origin\": {\"name\": \"crm\", \"version\": \"2.0.0\"}, "
                + "\"entityTypes\": {\"Lead\": {\"attributes\": {\"source\": {\"type\": \"string\"}}}}}";
        fixture.call("cairn_register_schema", args("document", other));

        JsonNode origins = fixture.call("cairn_describe").get("origins");

        assertThat(origins).map(origin -> origin.get("name").stringValue()).containsExactly("crm", "reference-model");
        assertThat(origins).allSatisfy(origin -> assertThat(fixture.store.schema()
                .storedDefinition(origin.get("name").stringValue()).orElseThrow().origin().version())
                .isEqualTo(origin.get("version").stringValue()));
    }

    // ------------------------------------------------------------------ JSON Schemas and explanations

    @Test
    void theJsonSchemasAreThoseOfTheDocumentsModule() {
        assertThat(fixture.tools.jsonSchema("{\"kind\": \"document\"}")).isEqualTo(SchemaDocuments.jsonSchema());
        assertThat(fixture.tools.jsonSchema("{\"kind\": \"payload\"}"))
                .isEqualTo(JsonSchemas.transactions(fixture.store.schema().current()));
        assertThat(fixture.tools.jsonSchema("{\"kind\": \"values\", \"type\": \"owns\"}"))
                .isEqualTo(JsonSchemas.values(fixture.store.schema().current(), "Owns"));
    }

    @Test
    void theJsonSchemaOfPayloadsFollowsTheSchemaOfTheStore() {
        assertThat(fixture.call("cairn_json_schema", args("kind", "values", "type", "Person")).get("properties")
                .has("email")).isFalse();
        fixture.call("cairn_register_schema", args("document", fixture.documentOf("reference-model")
                .replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("        type: date\n", "        type: date\n      email:\n        type: string\n")));

        assertThat(fixture.call("cairn_json_schema", args("kind", "values", "type", "Person")).get("properties")
                .has("email")).isTrue();
    }

    @Test
    void anUnknownTypeNameIsAnUnknownEntityTypeProblemAndNotAnException() {
        JsonNode result = fixture.call("cairn_json_schema", args("kind", "values", "type", "Orgnization"));

        JsonNode problem = problem(result, "UNKNOWN_ENTITY_TYPE");
        assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.type");
        assertThat(problem.get("hint").stringValue()).contains("Organization");
        assertThat(problem.get("expected").stringValue()).contains("Organization", "Owns");
    }

    @Test
    void theJsonSchemaOfValuesNeedsATypeAndAKindThatExists() {
        assertThat(problem(fixture.call("cairn_json_schema", args("kind", "values")), "MISSING_KEY").get("path")
                .stringValue()).isEqualTo("@arguments.type");
        JsonNode wrongKind = fixture.call("cairn_json_schema", args("kind", "payloads"));
        assertThat(problem(wrongKind, "INVALID_VALUE").get("hint").stringValue()).contains("payload");
        assertThat(problem(fixture.call("cairn_json_schema"), "MISSING_KEY").get("path").stringValue())
                .isEqualTo("@arguments.kind");
    }

    @Test
    void aProblemCodeIsExplained() {
        JsonNode explanation = fixture.call("cairn_explain_problem", args("code", "unknown_attribute"));

        assertThat(explanation.propertyNames()).containsExactlyInAnyOrder("code", "explanation");
        assertThat(explanation.get("code").stringValue()).isEqualTo("UNKNOWN_ATTRIBUTE");
        assertThat(explanation.get("explanation").stringValue()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE.explanation());
    }

    @Test
    void theCodeOfARuleIsExplained() {
        JsonNode explanation = fixture.call("cairn_explain_problem",
                args("code", "lei-checksum/invalid-check-digits"));

        assertThat(explanation.get("code").stringValue()).isEqualTo("lei-checksum/invalid-check-digits");
        assertThat(explanation.get("explanation").stringValue()).contains("check digits");
    }

    @Test
    void anUnknownCodeIsAProblemThatSuggestsTheClosestOne() {
        JsonNode misspelled = fixture.call("cairn_explain_problem", args("code", "UNKNOWN_ATTRIBUT"));
        JsonNode unknown = fixture.call("cairn_explain_problem", args("code", "no such code at all"));

        assertThat(problem(misspelled, "INVALID_VALUE").get("hint").stringValue()).contains("UNKNOWN_ATTRIBUTE");
        assertThat(problem(unknown, "INVALID_VALUE").get("hint").stringValue()).contains("cairn_describe");
    }

    // ------------------------------------------------------------------ schema documents

    @Test
    void aPlanOfADocumentThatChangesNothingSaysSo() {
        JsonNode plan = fixture.call("cairn_plan_schema", args("document", fixture.documentOf("reference-model")));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("unchanged");
        assertThat(plan.get("changes")).isEmpty();
        assertThat(plan.get("problems")).isEmpty();
        assertThat(fixture.call("cairn_register_schema", args("document", fixture.documentOf("reference-model")))
                .get("outcome").stringValue()).isEqualTo("unchanged");
    }

    @Test
    void aPlanOfABreakingChangeHasTheChangeWithItsHintAndTheLocatedProblem() {
        JsonNode plan = fixture.call("cairn_plan_schema", args("document", AgentFixture.documentWithoutBirthDate()));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(plan.get("changes")).singleElement().satisfies(change -> {
            assertThat(change.get("path").stringValue()).isEqualTo("Person.birthDate");
            assertThat(change.get("compatible").booleanValue()).isFalse();
            assertThat(change.get("hint").stringValue()).contains("deprecated");
        });
        assertThat(codes(plan)).containsExactly("BREAKING_CHANGE");
        assertThat(plan.get("problems").get(0).get("location").get("line").intValue()).isPositive();
    }

    @Test
    void aProblemAboutAnElementOfTheDocumentIsLocatedAtThatElement() {
        String document = fixture.documentOf("reference-model").replace("version: \"1.0.0\"", "version: \"1.1.0\"")
                .replace("supertype: Party\n    attributes:\n      birthDate", "supertype: Parti\n    attributes:\n      birthDate");

        JsonNode plan = fixture.call("cairn_plan_schema", args("document", document));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("rejected");
        JsonNode problem = problem(plan, "UNKNOWN_REFERENCE");
        assertThat(problem.get("location").get("pointer").stringValue()).isEqualTo("/entityTypes/Person");
    }

    @Test
    void aDocumentThatCannotBeReadIsRejectedWithLocatedProblems() {
        JsonNode syntax = fixture.call("cairn_register_schema", args("document", "cairn: 1\norigin: [\n"));
        JsonNode key = fixture.call("cairn_plan_schema", args("document",
                fixture.documentOf("reference-model").replace("entityTypes:", "entitiTypes:")));

        assertThat(syntax.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(codes(syntax)).containsExactly("DOCUMENT_SYNTAX");
        assertThat(syntax.get("problems").get(0).get("location").get("line").intValue()).isPositive();
        assertThat(key.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(key.get("changes")).isEmpty();
        assertThat(problem(key, "UNKNOWN_KEY").get("hint").stringValue()).contains("entityTypes");
        assertThat(fixture.store.schema().history()).hasSize(1);
    }

    // ------------------------------------------------------------------ data

    @Test
    void aCommitThatTheStoreRejectsHasTheProblemsLocatedInThePayload() {
        String payload = payload(createOrganization("ACME").replace("\"name\": \"ACME\"",
                "\"name\": \"ACME\", \"lei\": \"5493001KJTIIGC8Y1R13\""));

        JsonNode commit = fixture.call("cairn_commit", args("payload", payload));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(commit.has("receipt")).isFalse();
        JsonNode problem = problem(commit, "RULE_VIOLATION");
        assertThat(problem.get("ruleCode").stringValue()).isEqualTo("lei-checksum/invalid-check-digits");
        assertThat(problem.get("location").get("pointer").stringValue()).startsWith("/writes/0");
        assertThat(fixture.store.transactions()).isEmpty();
    }

    @Test
    void aPayloadThatPatchesAnEntityIsReadWithTheTypesOfTheStore() {
        EntityId alice = entity(fixture.createParties(), ALICE);
        String patch = payload("{\"patch\": {\"target\": \"" + alice.value() + "\", \"from\": \"2026-01-01T00:00:00Z\", "
                + "\"set\": {\"birthDate\": \"1990-05-17\"}}}");

        JsonNode commit = fixture.call("cairn_commit", args("payload", patch));

        assertThat(commit.get("outcome").stringValue()).isEqualTo("committed");
        JsonNode person = fixture.call("cairn_read_entity",
                args("id", alice.value(), "effectiveAt", "2026-02-01T00:00:00Z")).get("result");
        assertThat(person.get("values").get("birthDate").stringValue()).isEqualTo("1990-05-17");
    }

    @Test
    void aPayloadWithAnOffsetInstantIsReadAndAnUnknownIdIsReportedByTheStore() {
        String unknown = payload("{\"patch\": {\"target\": \"00000000-0000-7000-8000-0000000000ff\", "
                + "\"from\": \"2026-01-01T01:00:00+01:00\", \"set\": {\"name\": \"X\"}}}");

        JsonNode dryRun = fixture.call("cairn_dry_run", args("payload", unknown));

        assertThat(codes(dryRun)).containsExactly("UNKNOWN_ENTITY");
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyGetsTheFirstReceipt() {
        String keyed = payload(createOrganization("ACME")).replace("\"writes\"", "\"idempotencyKey\": \"k1\", \"writes\"");

        JsonNode first = fixture.call("cairn_commit", args("payload", keyed));
        JsonNode retry = fixture.call("cairn_commit", args("payload", keyed));

        assertThat(retry.get("receipt")).isEqualTo(first.get("receipt"));
        assertThat(fixture.store.transactions()).hasSize(1);
    }

    // ------------------------------------------------------------------ reading

    private record Ownership(EntityId acme, EntityId beta, EntityId alice, RelationshipId owns) {
    }

    /** RD-1, RD-2 and the correction of RD-2: ACME owns 60% of Beta from 1 February; recorded 2 May as 55%. */
    private Ownership recordOwnership() {
        Receipt parties = fixture.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt ownership = fixture.replay(ReferenceStories.rd2Ownership(acme, beta));
        RelationshipId owns = relationship(ownership, ACME_OWNS_BETA);
        fixture.replay(ReferenceStories.rd2ShareCorrection(owns));
        return new Ownership(acme, beta, entity(parties, ALICE), owns);
    }

    @Test
    void anEntityIsReadAtAnEffectiveTime() {
        Ownership data = recordOwnership();

        JsonNode now = fixture.call("cairn_read_entity", args("id", data.acme.value(), "effectiveAt", MARCH_1));
        JsonNode before = fixture.call("cairn_read_entity",
                args("id", data.acme.value(), "effectiveAt", "2025-12-01T00:00:00Z"));

        assertThat(now.get("result").get("entityType").stringValue()).isEqualTo("Organization");
        assertThat(now.get("result").get("values").get("name").stringValue()).isEqualTo("ACME");
        assertThat(now.get("effectiveAt").stringValue()).isEqualTo(MARCH_1);
        assertThat(now.get("basis").longValue()).isEqualTo(fixture.store.latestBasis().value());
        assertThat(before.get("result").isNull()).isTrue();
        assertThat(before.get("basis").longValue()).isEqualTo(now.get("basis").longValue());
    }

    @Test
    void anOffsetInTheEffectiveTimeIsConvertedToUtc() {
        Ownership data = recordOwnership();

        JsonNode read = fixture.call("cairn_read_entity",
                args("id", data.acme.value(), "effectiveAt", "2026-03-01T01:00:00+01:00"));

        assertThat(read.get("effectiveAt").stringValue()).isEqualTo(MARCH_1);
    }

    @Test
    void aRelationshipIsReadAsKnownAtABasisAndAtATransactionTime() {
        Ownership data = recordOwnership();
        ObjectNode atBasis = args("id", data.owns.value(), "effectiveAt", MARCH_1);
        atBasis.putObject("knownAt").put("basis", 3);
        ObjectNode atTime = args("id", data.owns.value(), "effectiveAt", MARCH_1);
        atTime.putObject("knownAt").put("transactionTime", "2026-04-01T00:00:00Z");

        JsonNode latest = fixture.call("cairn_read_relationship",
                args("id", data.owns.value(), "effectiveAt", MARCH_1));
        JsonNode first = fixture.call("cairn_read_relationship", atBasis);
        JsonNode beforeCorrection = fixture.call("cairn_read_relationship", atTime);

        assertThat(latest.get("result").get("values").get("share").decimalValue()).isEqualByComparingTo("55");
        assertThat(first.get("result").get("values").get("share").decimalValue()).isEqualByComparingTo("60");
        assertThat(first.get("basis").longValue()).isEqualTo(3);
        assertThat(beforeCorrection.get("result").get("values").get("share").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(beforeCorrection.get("basis").longValue()).isEqualTo(3);
        assertThat(latest.get("basis").longValue()).isEqualTo(4);
        assertThat(latest.get("result").get("source").stringValue()).isEqualTo(data.acme.value());
        assertThat(latest.get("result").get("target").stringValue()).isEqualTo(data.beta.value());
    }

    @Test
    void theEntitiesOfATypeIncludeThoseOfItsSubtypes() {
        recordOwnership();

        JsonNode parties = fixture.call("cairn_list_entities", args("type", "Party", "effectiveAt", MARCH_1));
        JsonNode organizations = fixture.call("cairn_list_entities",
                args("type", "organization", "effectiveAt", MARCH_1));

        assertThat(parties.get("result")).hasSize(3);
        assertThat(organizations.get("result")).map(state -> state.get("values").get("name").stringValue())
                .containsExactly("ACME", "Beta");
        assertThat(parties.get("effectiveAt").stringValue()).isEqualTo(MARCH_1);
    }

    @Test
    void anUnknownEntityTypeInAListIsAProblemWithAHint() {
        JsonNode result = fixture.call("cairn_list_entities", args("type", "Orgnization", "effectiveAt", MARCH_1));

        assertThat(problem(result, "UNKNOWN_ENTITY_TYPE").get("hint").stringValue()).contains("Organization");
    }

    @Test
    void aTimelineHasEveryVersionAsKnownAtTheBasis() {
        Ownership data = recordOwnership();
        ObjectNode before = args("id", data.owns.value());
        before.putObject("knownAt").put("basis", 3);

        JsonNode latest = fixture.call("cairn_timeline", args("id", data.owns.value()));
        JsonNode earlier = fixture.call("cairn_timeline", before);
        JsonNode entity = fixture.call("cairn_timeline", args("id", data.acme.value()));

        assertThat(latest.get("kind").stringValue()).isEqualTo("relationship");
        assertThat(latest.has("effectiveAt")).isFalse();
        assertThat(latest.get("basis").longValue()).isEqualTo(4);
        assertThat(latest.get("result")).map(version -> version.get("values").get("share").decimalValue().intValue())
                .containsExactly(55);
        assertThat(earlier.get("basis").longValue()).isEqualTo(3);
        assertThat(earlier.get("result")).map(version -> version.get("values").get("share").decimalValue().intValue())
                .containsExactly(60);
        assertThat(entity.get("kind").stringValue()).isEqualTo("entity");
        assertThat(entity.get("result")).hasSize(1);
    }

    @Test
    void aTraversalFollowsTheRelationshipsUpToTheMaximumDepth() {
        Ownership data = recordOwnership();

        JsonNode owners = fixture.call("cairn_traverse", args("id", data.beta.value(), "name", "ownedBy",
                "effectiveAt", MARCH_1).put("maxDepth", 3));
        JsonNode none = fixture.call("cairn_traverse", args("id", data.beta.value(), "name", "ownedBy",
                "effectiveAt", MARCH_1).put("maxDepth", 0));
        JsonNode owned = fixture.call("cairn_traverse", args("id", data.acme.value(), "name", "Owns",
                "effectiveAt", MARCH_1).put("maxDepth", 1));

        assertThat(owners.get("result")).singleElement().satisfies(step -> {
            assertThat(step.get("entity").get("id").stringValue()).isEqualTo(data.acme.value());
            assertThat(step.get("depth").intValue()).isEqualTo(1);
            assertThat(step.get("via").get("id").stringValue()).isEqualTo(data.owns.value());
        });
        assertThat(none.get("result")).isEmpty();
        assertThat(owned.get("result")).singleElement()
                .satisfies(step -> assertThat(step.get("entity").get("id").stringValue()).isEqualTo(data.beta.value()));
    }

    @Test
    void navigationAtATimeBeforeTheRelationshipFindsNothing() {
        Ownership data = recordOwnership();

        JsonNode owners = fixture.call("cairn_navigate",
                args("id", data.beta.value(), "name", "ownedBy", "effectiveAt", "2026-01-15T00:00:00Z"));

        assertThat(owners.get("result")).isEmpty();
    }

    @Test
    void anIdThatIsNotOfTheRightKindIsAProblemThatSaysWhichToolToUse() {
        Ownership data = recordOwnership();

        JsonNode entityAsRelationship = fixture.call("cairn_read_relationship",
                args("id", data.acme.value(), "effectiveAt", MARCH_1));
        JsonNode relationshipAsEntity = fixture.call("cairn_read_entity",
                args("id", data.owns.value(), "effectiveAt", MARCH_1));
        JsonNode unknown = fixture.call("cairn_read_entity",
                args("id", "00000000-0000-7000-8000-0000000000ff", "effectiveAt", MARCH_1));
        JsonNode timeline = fixture.call("cairn_timeline", args("id", "no such id"));

        assertThat(problem(entityAsRelationship, "UNKNOWN_RELATIONSHIP").get("hint").stringValue())
                .contains("cairn_read_entity");
        assertThat(problem(relationshipAsEntity, "UNKNOWN_ENTITY").get("hint").stringValue())
                .contains("cairn_read_relationship");
        assertThat(problem(unknown, "UNKNOWN_ENTITY").get("path").stringValue()).isEqualTo("@arguments.id");
        assertThat(codes(timeline)).containsExactly("UNKNOWN_ENTITY");
    }

    @Test
    void anUnknownNavigationNameIsAProblemWithTheNamesThatExist() {
        Ownership data = recordOwnership();

        JsonNode navigate = fixture.call("cairn_navigate",
                args("id", data.beta.value(), "name", "ownedbi", "effectiveAt", MARCH_1));
        JsonNode traverse = fixture.call("cairn_traverse",
                args("id", data.beta.value(), "name", "boss", "effectiveAt", MARCH_1).put("maxDepth", 1));

        JsonNode problem = problem(navigate, "UNKNOWN_RELATIONSHIP_TYPE");
        assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.name");
        assertThat(problem.get("hint").stringValue()).contains("ownedBy");
        assertThat(problem.get("expected").stringValue()).contains("Owns", "ownedBy", "Employs");
        assertThat(codes(traverse)).containsExactly("UNKNOWN_RELATIONSHIP_TYPE");
    }

    // ------------------------------------------------------------------ wrong arguments

    @Test
    void argumentsThatAreNotAJsonObjectAreProblems() {
        assertThat(problem(Json.parse(fixture.tools.readEntity("{not json")), "DOCUMENT_SYNTAX").get("path")
                .stringValue()).isEqualTo("@arguments");
        assertThat(problem(Json.parse(fixture.tools.readEntity("[1]")), "INVALID_VALUE").get("path").stringValue())
                .isEqualTo("@arguments");
    }

    @Test
    void anArgumentThatTheToolDoesNotTakeIsAProblemWithTheClosestOneAsAHint() {
        JsonNode result = fixture.call("cairn_read_entity", args("id", "x", "effectivAt", MARCH_1));

        JsonNode unknown = problem(result, "UNKNOWN_KEY");
        assertThat(unknown.get("path").stringValue()).isEqualTo("@arguments.effectivAt");
        assertThat(unknown.get("hint").stringValue()).contains("effectiveAt");
        assertThat(unknown.get("expected").stringValue()).isEqualTo("id, key, effectiveAt, knownAt");
        assertThat(problem(result, "MISSING_KEY").get("path").stringValue()).isEqualTo("@arguments.effectiveAt");
    }

    @Test
    void anInstantThatIsNotIso8601IsAProblemWithAnExample() {
        JsonNode result = fixture.call("cairn_read_entity", args("id", "x", "effectiveAt", "1 March"));

        JsonNode problem = problem(result, "INVALID_VALUE");
        assertThat(problem.get("path").stringValue()).isEqualTo("@arguments.effectiveAt");
        assertThat(problem.get("found").stringValue()).isEqualTo("1 March");
        assertThat(problem.get("expected").stringValue()).contains("2026-03-01T00:00:00Z");
    }

    @Test
    void instantArgumentsHaveTheOneFormOfPayloads() {
        Ownership data = recordOwnership();

        for (String instant : new String[] {"2026-03-01T00:00:00+0100", "2026-03-01T00:00:00+01", "2026-03-01T00:00Z",
                "2026-03-01"}) {
            JsonNode effective = fixture.call("cairn_read_entity",
                    args("id", data.acme.value(), "effectiveAt", instant));
            ObjectNode atTime = args("id", data.acme.value(), "effectiveAt", MARCH_1);
            atTime.putObject("knownAt").put("transactionTime", instant);

            JsonNode problem = problem(effective, "INVALID_VALUE");
            assertThat(problem.get("path").stringValue()).as(instant).isEqualTo("@arguments.effectiveAt");
            assertThat(problem.get("found").stringValue()).isEqualTo(instant);
            assertThat(problem.get("expected").stringValue()).contains("2026-03-01T00:00:00Z");
            assertThat(problem.get("hint").stringValue()).contains("2026-03-01T00:00:00+01:00");
            assertThat(problem(fixture.call("cairn_read_entity", atTime), "INVALID_VALUE").get("path")
                    .stringValue()).as(instant).isEqualTo("@arguments.knownAt.transactionTime");
        }

        JsonNode read = fixture.call("cairn_read_entity",
                args("id", data.acme.value(), "effectiveAt", "2026-03-01T00:00:00+01:00"));

        assertThat(read.has("problems")).isFalse();
        assertThat(read.get("effectiveAt").stringValue()).isEqualTo("2026-02-28T23:00:00Z");
    }

    @Test
    void theInputSchemasGiveInstantsThePatternOfPayloads() {
        int instants = 0;
        for (AgentTools.ToolDescription tool : AgentTools.toolDescriptions()) {
            JsonNode properties = Json.parse(tool.inputSchema()).get("properties");
            if (properties.has("effectiveAt")) {
                JsonNode effectiveAt = properties.get("effectiveAt");
                JsonNode transactionTime = properties.get("knownAt").get("properties").get("transactionTime");
                for (JsonNode instant : new JsonNode[] {effectiveAt, transactionTime}) {
                    assertThat(instant.get("format").stringValue()).as(tool.name()).isEqualTo("date-time");
                    assertThat(instant.get("pattern").stringValue()).as(tool.name()).isEqualTo(Instants.PATTERN);
                    instants++;
                }
            }
        }
        assertThat(instants).as("the read tools").isGreaterThanOrEqualTo(2 * 5);
    }

    @Test
    void wrongKnownAtValuesAreProblems() {
        for (String knownAt : new String[] {"{\"basis\": -1}", "{\"basis\": 1.5}", "{\"basis\": \"2\"}", "{}",
                "{\"basis\": 1, \"transactionTime\": \"2026-04-01T00:00:00Z\"}", "{\"base\": 1}", "\"now\"",
                "{\"transactionTime\": \"April\"}", "{\"transactionTime\": 5}"}) {
            ObjectNode arguments = args("id", "x", "effectiveAt", MARCH_1);
            arguments.set("knownAt", Json.parse(knownAt));

            JsonNode result = fixture.call("cairn_read_entity", arguments);

            assertThat(result.get("problems")).as(knownAt).isNotEmpty().allSatisfy(problem ->
                    assertThat(problem.get("path").stringValue()).startsWith("@arguments.knownAt"));
        }
        ObjectNode misspelled = args("id", "x", "effectiveAt", MARCH_1);
        misspelled.putObject("knownAt").put("base", 1);
        assertThat(problem(fixture.call("cairn_read_entity", misspelled), "UNKNOWN_KEY").get("hint").stringValue())
                .contains("basis");
    }

    @Test
    void aMaximumDepthThatIsNotAWholeNumberOfAtLeastZeroIsAProblem() {
        for (JsonNode depth : new JsonNode[] {Json.parse("-1"), Json.parse("1.5"), Json.parse("\"3\""),
                Json.parse("99999999999")}) {
            ObjectNode arguments = args("id", "x", "name", "ownedBy", "effectiveAt", MARCH_1);
            arguments.set("maxDepth", depth);

            assertThat(problem(fixture.call("cairn_traverse", arguments), "INVALID_VALUE").get("path").stringValue())
                    .as(depth.toString()).isEqualTo("@arguments.maxDepth");
        }
    }

    @Test
    void aTraversalWithoutAMaximumDepthOrWithANullOneFollowsToTheEnd() {
        Ownership data = recordOwnership();
        ObjectNode arguments = args("id", data.beta.value(), "name", "ownedBy", "effectiveAt", MARCH_1);

        JsonNode toTheEnd = fixture.call("cairn_traverse", arguments);
        JsonNode explicitNull = fixture.call("cairn_traverse", arguments.deepCopy().putNull("maxDepth"));
        JsonNode deep = fixture.call("cairn_traverse", arguments.deepCopy().put("maxDepth", 3));
        JsonNode none = fixture.call("cairn_traverse", arguments.deepCopy().put("maxDepth", 0));

        assertThat(toTheEnd.has("problems")).isFalse();
        assertThat(toTheEnd.get("result")).singleElement().satisfies(step ->
                assertThat(step.get("entity").get("id").stringValue()).isEqualTo(data.acme.value()));
        assertThat(explicitNull).as("a null depth is none").isEqualTo(toTheEnd);
        assertThat(deep).as("the same as a depth that is enough").isEqualTo(toTheEnd);
        assertThat(none.get("result")).as("a depth of 0 still follows none").isEmpty();
    }

    @Test
    void wrongArgumentsGetTheShapeOfTheResultOfTheTool() {
        assertThat(shape("cairn_plan_schema", "{}")).containsExactly("outcome", "changes", "problems");
        for (String tool : List.of("cairn_register_schema", "cairn_dry_run", "cairn_commit")) {
            assertThat(shape(tool, "{}")).as(tool).containsExactly("outcome", "problems");
        }
        for (String tool : List.of("cairn_json_schema", "cairn_explain_problem", "cairn_read_entity",
                "cairn_read_relationship", "cairn_list_entities", "cairn_timeline", "cairn_navigate",
                "cairn_traverse", "cairn_query", "cairn_approval_status")) {
            assertThat(shape(tool, "{}")).as(tool).containsExactly("problems");
        }
        assertThat(shape("cairn_list_changes", "{}")).as("no argument is required").containsExactly("changes", "next",
                "latestBasis");
        assertThat(shape("cairn_list_changes", "{\"after\": \"x\"}")).containsExactly("problems");
        assertThat(shape("cairn_describe", "{\"extra\": 1}")).containsExactly("problems");
    }

    @Test
    void aPlanOfWrongArgumentsIsRejectedWithNoChanges() {
        JsonNode plan = fixture.call("cairn_plan_schema", args("document", "x").put("extra", 1));
        JsonNode notAnObject = Json.parse(fixture.tools.planSchema("[1]"));

        assertThat(plan.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(plan.get("changes")).isEmpty();
        assertThat(codes(plan)).containsExactly("UNKNOWN_KEY");
        assertThat(notAnObject.get("outcome").stringValue()).isEqualTo("rejected");
        assertThat(notAnObject.get("problems").get(0).get("path").stringValue()).isEqualTo("@arguments");
    }

    /** The names of the fields of the result of a tool called with some arguments, in order. */
    private List<String> shape(String tool, String arguments) {
        List<String> names = new ArrayList<>();
        Json.parse(fixture.tools.call(tool, arguments)).propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void aStatusRequestForAnUnknownRequestIsAProblem() {
        JsonNode result = fixture.call("cairn_approval_status", args("requestId", "nope"));

        assertThat(problem(result, "INVALID_VALUE").get("path").stringValue()).isEqualTo("@arguments.requestId");
    }

    @Test
    void theJsonOfTheResultsKeepsTheScaleOfDecimals() {
        Ownership data = recordOwnership();
        String payload = payload("{\"patch\": {\"target\": \"" + data.owns.value() + "\", "
                + "\"from\": \"2026-02-01T00:00:00Z\", \"set\": {\"share\": 55.50}}}");
        fixture.call("cairn_commit", args("payload", payload));

        String text = fixture.tools.readRelationship(Json.render(args("id", data.owns.value(), "effectiveAt", MARCH_1)));

        assertThat(text).contains("\"share\":55.50");
    }
}
