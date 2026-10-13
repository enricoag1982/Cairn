package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.createOrganization;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code json-payloads} spec, one test each, in the reference domain: reading transaction payloads,
 * locating their problems, the JSON forms of results, and the generated JSON Schemas.
 */
class JsonPayloadScenariosTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    // ------------------------------------------------------------------ transaction payloads

    @Test
    @DisplayName("Scenario: Creating organizations from a payload")
    void creatingOrganizationsFromAPayload() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "temporaryId": "acme", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "ACME" } } },
                    { "create": { "temporaryId": "beta", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "Beta" } } }
                  ]
                }
                """;

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        CommitResult result = reference.store.commit(read.transaction());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        assertThat(receipt.principal()).isEqualTo("agent:onboarding");
        assertThat(receipt.temporaryIds()).containsOnlyKeys(new TemporaryId("acme"), new TemporaryId("beta"));
        assertThat(receipt.temporaryIds().values()).allSatisfy(id -> assertThat(id).isInstanceOf(EntityId.class));
        assertThat(receipt.temporaryIds().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Scenario: A date value")
    void aDateValue() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "entityType": "Person", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "Alice", "birthDate": "1990-05-17" } } }
                  ]
                }
                """;

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema());

        assertThat(read.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Create.class,
                create -> assertThat(create.values()).containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)))
                        .isEqualTo(Map.of("name", Value.of("Alice"), "birthDate", Value.of(LocalDate.of(1990, 5, 17)))));
    }

    @Test
    @DisplayName("Scenario: Unknown write kind")
    void unknownWriteKind() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "ACME" } } },
                    { "upsert": { "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "Beta" } } }
                  ]
                }
                """;

        PayloadReadResult result = Payloads.readTransaction(payload, reference.schema());

        assertThat(result).isInstanceOf(PayloadReadResult.Invalid.class);
        List<Problem> problems = ((PayloadReadResult.Invalid) result).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
            assertThat(problem.found()).contains("upsert");
            assertThat(problem.location()).contains(locationOf(payload, "{ \"upsert\"", "/writes/1"));
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint)
                    .contains("create", "relate", "patch", "replace", "endDate"));
        });
    }

    @Test
    @DisplayName("Scenario: Patching a stored relationship by its ID")
    void patchingAStoredRelationshipByItsId() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(
                parties.temporaryIds().get(ReferenceStories.ACME), parties.temporaryIds().get(ReferenceStories.BETA)));
        RelationshipId owns = (RelationshipId) ownership.temporaryIds().get(ReferenceStories.ACME_OWNS_BETA);
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "patch": { "target": "%s", "from": "2026-02-01T00:00:00Z", "set": { "share": 55 } } }
                  ]
                }
                """.formatted(owns.value());

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), IdLookup.from(reference.latest()));

        assertThat(read.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Patch.class, patch -> {
            assertThat(patch.target()).isEqualTo(owns);
            assertThat(patch.set()).containsOnlyKeys("share");
            assertThat(patch.set().get("share")).isInstanceOf(Value.DecimalValue.class)
                    .isEqualTo(Value.of(new BigDecimal("55")));
        });
    }

    @Test
    @DisplayName("Scenario: A replace without values")
    void aReplaceWithoutValues() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId acme = (EntityId) parties.temporaryIds().get(ReferenceStories.ACME);
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "replace": { "target": "%s", "from": "2026-02-01T00:00:00Z" } }
                  ]
                }
                """.formatted(acme.value());

        PayloadReadResult result = Payloads.readTransaction(payload, reference.schema(), reference.lookup());

        assertThat(result).isInstanceOf(PayloadReadResult.Invalid.class);
        assertThat(((PayloadReadResult.Invalid) result).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_KEY);
            assertThat(problem.path()).isEqualTo("@write.0");
            assertThat(problem.expected()).contains("the key 'values'");
            assertThat(problem.location()).contains(locationOf(payload, "{ \"target\"", "/writes/0/replace"));
        });
    }

    // ------------------------------------------------------------------ problems are located

    @Test
    @DisplayName("Scenario: Locating a value problem")
    void locatingAValueProblem() {
        // An account has exactly one holder, so the payload also relates account A-1 to Beta.
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "temporaryId": "beta", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "Beta" } } },
                    { "create": { "temporaryId": "a1", "entityType": "Account", "from": "2026-01-01T00:00:00Z",
                                  "values": { "number": "A-1", "currency": "eur", "balance": 1000 } } },
                    { "relate": { "relationshipType": "HeldBy", "source": "$a1", "target": "$beta",
                                  "from": "2026-01-01T00:00:00Z" } }
                  ]
                }
                """;
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());

        DryRunResult result = reference.store.dryRun(read.transaction());

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        List<Problem> located = read.locate(((DryRunResult.Rejected) result).problems());
        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_PATTERN_MISMATCH);
            assertThat(problem.path()).isEqualTo("$a1.currency");
            assertThat(problem.location()).contains(locationOf(payload, "\"currency\"",
                    "/writes/1/create/values/currency"));
        });
    }

    // ------------------------------------------------------------------ results as JSON

    @Test
    @DisplayName("Scenario: Reading back what was written")
    void readingBackWhatWasWritten() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId acme = (EntityId) parties.temporaryIds().get(ReferenceStories.ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        EntityState state = reference.latest().entity(acme).orElseThrow();
        assertThat(state.values()).containsOnlyKeys("name", "lei");

        Object written = DocumentTrees.load(Payloads.toJson(state));
        String values = DocumentTrees.toJson(DocumentTrees.at(written, "values"));
        String replacePayload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "replace": { "target": "%s", "from": "2026-02-01T00:00:00Z", "values": %s } }
                  ]
                }
                """.formatted(acme.value(), values);
        TransactionPayload read = PayloadFixtures.read(replacePayload, reference.schema(), reference.lookup());

        assertThat(read.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Replace.class,
                replace -> {
                    assertThat(replace.target()).isEqualTo(acme);
                    assertThat(replace.values()).isEqualTo(state.values());
                });
    }

    @Test
    @DisplayName("Scenario: A receipt as JSON")
    void aReceiptAsJson() {
        String payload = payload(createOrganization("acme", "ACME"));
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        Receipt receipt = reference.commit(read.transaction());
        EntityId acme = (EntityId) receipt.temporaryIds().get(new TemporaryId("acme"));

        Object json = DocumentTrees.load(Payloads.toJson(receipt));

        assertThat(DocumentTrees.at(json, "temporaryIds")).isEqualTo(Map.of("$acme", Map.of("entity", acme.value())));
        assertThat(DocumentTrees.at(json, "created")).isEqualTo(List.of(Map.of("entity", acme.value())));
    }

    @Test
    @DisplayName("Scenario: Problems as JSON")
    void problemsAsJson() {
        String payload = payload("{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"ACME\", \"nmae\": \"ACME\" } } }");
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        List<Problem> rejected = reference.rejectedProblems(read.transaction());
        Problem located = read.locate(rejected).stream()
                .filter(problem -> problem.code() == ProblemCode.UNKNOWN_ATTRIBUTE).findFirst().orElseThrow();
        assertThat(located.hint()).isPresent();
        assertThat(located.location()).isPresent();

        Object json = DocumentTrees.load(Payloads.toJson(located));

        DocumentLocation at = located.location().orElseThrow();
        assertThat(DocumentTrees.at(json, "code")).isEqualTo("UNKNOWN_ATTRIBUTE");
        assertThat(DocumentTrees.at(json, "path")).isEqualTo("$acme.nmae");
        assertThat(DocumentTrees.at(json, "message")).isEqualTo(located.message());
        assertThat(DocumentTrees.at(json, "expected")).isEqualTo(located.expected().orElseThrow());
        assertThat(DocumentTrees.at(json, "found")).isEqualTo(located.found().orElseThrow());
        assertThat(DocumentTrees.at(json, "hint")).isEqualTo(located.hint().orElseThrow());
        assertThat(DocumentTrees.at(json, "location", "pointer")).isEqualTo("/writes/0/create/values/nmae");
        assertThat(DocumentTrees.at(json, "location", "pointer")).isEqualTo(at.pointer());
        assertThat(DocumentTrees.at(json, "location", "line")).isEqualTo(at.line());
        assertThat(DocumentTrees.at(json, "location", "column")).isEqualTo(at.column());
    }

    // ------------------------------------------------------------------ generated JSON Schemas

    @Test
    @DisplayName("Scenario: Values of Organization")
    void valuesOfOrganization() {
        String schema = JsonSchemas.values(reference.schema(), "Organization");

        Object json = DocumentTrees.load(schema);

        assertThat(DocumentTrees.at(json, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(DocumentTrees.at(json, "required")).isEqualTo(List.of("name"));
        assertThat(DocumentTrees.at(json, "properties", "lei", "type")).isEqualTo("string");
        assertThat(DocumentTrees.at(json, "properties", "lei", "pattern")).isEqualTo("^(?:[A-Z0-9]{18}[0-9]{2})$");
        assertThat(DocumentTrees.at(json, "additionalProperties")).isEqualTo(false);
        assertThat(DocumentTrees.keysAt(json, "properties")).containsExactlyInAnyOrder("name", "lei");
        assertThat(GeneratedSchemaCheck.isValid(schema, "{\"name\": \"ACME\", \"lei\": \"5493001KJTIIGC8Y1R12\"}"))
                .isTrue();
        assertThat(GeneratedSchemaCheck.isValid(schema, "{\"name\": \"ACME\", \"lei\": \"5493001kjtiigc8y1r12\"}"))
                .isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, "{\"lei\": \"5493001KJTIIGC8Y1R12\"}")).isFalse();
        assertThat(GeneratedSchemaCheck.isValid(schema, "{\"name\": \"ACME\", \"nmae\": \"ACME\"}")).isFalse();
    }

    @Test
    @DisplayName("Scenario: A payload that breaks the schema")
    void aPayloadThatBreaksTheSchema() {
        String schema = JsonSchemas.transactions(reference.schema());
        String broken = payload("{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"nmae\": \"ACME\" } } }");

        assertThat(GeneratedSchemaCheck.errors(schema, broken)).isNotEmpty();
        assertThat(GeneratedSchemaCheck.errors(schema, payload(createOrganization("acme", "ACME")))).isEmpty();
    }
}
