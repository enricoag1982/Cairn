package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The derived attributes in payloads and generated JSON Schemas (capability {@code derived-attributes}, design D6 of
 * {@code add-derived-attributes}): a payload that sets one is read and rejected by the store at the value in the
 * payload, the schema of a type's values lists them as read-only, and the schema of payloads leaves them out of writes.
 * The scenarios are shown in the reference domain, in which Account declares the test-only derived attribute
 * {@code label}.
 */
class DerivedAttributesPayloadsTest {

    private static final String LABEL = "self.number + ' ' + self.currency";
    private static final String CREATE_ACCOUNT = "{ \"create\": { \"temporaryId\": \"a9\", \"entityType\": \"Account\", "
            + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"number\": \"A-9\", \"currency\": \"EUR\", "
            + "\"balance\": 5%s } } }";

    private static SchemaDefinition accountWithLabel(boolean required) {
        return withDerivedAttribute(ReferenceModel.definition(), "Account", "label", ValueType.STRING, required, LABEL);
    }

    private static String createAccount(String extraValues) {
        return CREATE_ACCOUNT.formatted(extraValues);
    }

    // ------------------------------------------------------------------ the scenarios

    @Test
    @DisplayName("Scenario: Setting a derived attribute")
    void settingADerivedAttribute() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(false));
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(ReferenceStories.BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
        EntityId a1 = (EntityId) accounts.temporaryIds().get(ReferenceStories.A1);
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "patch": { "target": "%s", "from": "2026-04-01T00:00:00Z", "set": { "label": "mine" } } }
                  ]
                }
                """.formatted(a1.value());
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());

        List<Problem> problems = reference.rejectedProblems(read.transaction());

        List<Problem> located = read.locate(problems);
        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
            assertThat(problem.path()).isEqualTo(a1.value() + ".label");
            assertThat(problem.location()).contains(locationOf(payload, "\"label\"", "/writes/0/patch/set/label"));
        });
    }

    @Test
    @DisplayName("Scenario: A derived attribute in the payload schema")
    void aDerivedAttributeInThePayloadSchema() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(false));
        String schema = JsonSchemas.transactions(reference.schema());

        assertThat(GeneratedSchemaCheck.errors(schema, payload(createAccount("")))).as("without a label").isEmpty();
        assertThat(GeneratedSchemaCheck.errors(schema, payload(createAccount(", \"label\": \"mine\""))))
                .as("with a label").isNotEmpty();
    }

    // ------------------------------------------------------------------ what the payload schema says

    @Test
    void aRequiredDerivedAttributeIsNotRequiredInTheValuesOfACreate() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(true));
        String schema = JsonSchemas.transactions(reference.schema());
        Object tree = DocumentTrees.load(schema);

        assertThat(DocumentTrees.at(tree, "$defs", "entityValues.Account", "required"))
                .isEqualTo(List.of("number", "currency", "balance"));
        assertThat(DocumentTrees.keysAt(tree, "$defs", "entityValues.Account", "properties"))
                .containsExactly("number", "currency", "balance");
        assertThat(GeneratedSchemaCheck.errors(schema, payload(createAccount("")))).isEmpty();
    }

    @Test
    void aTypeWhoseOnlyRequiredAttributeIsDerivedNeedsNoValuesInACreate() {
        SchemaDefinition model = withDerivedAttribute(SchemaDefinition.builder()
                .entityType("Thing", e -> e.attribute("name", ValueType.STRING)).build(), "Thing", "shout",
                ValueType.STRING, true, "has(self.name) ? self.name : ''");
        Schema schema = PayloadFixtures.schemaOf(model);
        String json = JsonSchemas.transactions(schema);

        String withoutValues = payload("{ \"create\": { \"entityType\": \"Thing\", \"from\": \"2026-01-01T00:00:00Z\" } }");

        assertThat(GeneratedSchemaCheck.errors(json, withoutValues)).isEmpty();
    }

    @Test
    void thePatchAndReplaceSchemasDoNotNameDerivedAttributes() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(false));
        String schema = JsonSchemas.transactions(reference.schema());
        Object tree = DocumentTrees.load(schema);
        String patch = payload("{ \"patch\": { \"target\": \"$a9\", \"from\": \"2026-04-01T00:00:00Z\", "
                + "\"set\": { \"balance\": 1 }, \"clear\": [\"label\"] } }");
        String replace = payload("{ \"replace\": { \"target\": \"$a9\", \"from\": \"2026-04-01T00:00:00Z\", "
                + "\"values\": { \"number\": \"A-9\", \"label\": \"mine\" } } }");

        assertThat(names(tree)).doesNotContain("label").contains("balance");
        assertThat(DocumentTrees.keysAt(tree, "$defs", "attributeValues", "properties")).doesNotContain("label");
        assertThat(GeneratedSchemaCheck.errors(schema, patch)).as("clearing a derived attribute").isNotEmpty();
        assertThat(GeneratedSchemaCheck.errors(schema, replace)).as("replacing with a derived attribute").isNotEmpty();
    }

    @Test
    void aNameThatIsDerivedInOneTypeAndPlainInAnotherCanStillBeSet() {
        SchemaDefinition model = withDerivedAttribute(ReferenceModel.definition(), "Account", "name", ValueType.STRING,
                false, "self.number");
        Schema schema = new ReferenceDomainStore(model).schema();
        Object tree = DocumentTrees.load(JsonSchemas.transactions(schema));

        assertThat(names(tree)).contains("name");
        assertThat(DocumentTrees.keysAt(tree, "$defs", "entityValues.Account", "properties")).doesNotContain("name");
        assertThat(DocumentTrees.keysAt(tree, "$defs", "entityValues.Organization", "properties")).contains("name");
    }

    // ------------------------------------------------------------------ what the values schema says

    @Test
    void theSchemaOfTheValuesOfATypeListsDerivedAttributesAsReadOnly() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(true));
        String schema = JsonSchemas.values(reference.schema(), "Account");
        Object tree = DocumentTrees.load(schema);

        assertThat(DocumentTrees.keysAt(tree, "properties")).containsExactly("number", "currency", "balance", "label");
        assertThat(DocumentTrees.at(tree, "required")).isEqualTo(List.of("number", "currency", "balance", "label"));
        assertThat(DocumentTrees.at(tree, "properties", "label", "readOnly")).isEqualTo(true);
        assertThat(DocumentTrees.at(tree, "properties", "label", "type")).isEqualTo("string");
        assertThat(String.valueOf(DocumentTrees.at(tree, "properties", "label", "description"))).contains(LABEL);
        assertThat(DocumentTrees.mapAt(DocumentTrees.at(tree, "properties", "number")).containsKey("readOnly")).isFalse();
        assertThat(GeneratedSchemaCheck.dialectErrors(schema)).isEmpty();
        assertThat(GeneratedSchemaCheck.isValid(schema,
                "{\"number\": \"A-1\", \"currency\": \"EUR\", \"balance\": 5, \"label\": \"A-1 EUR\"}")).isTrue();
    }

    @Test
    void theValuesOfAStateWrittenAsJsonAreValidAgainstTheValuesSchema() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(true));
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(ReferenceStories.BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
        EntityId a1 = (EntityId) accounts.temporaryIds().get(ReferenceStories.A1);
        var state = reference.latest().entity(a1).orElseThrow();

        String json = Payloads.toJson(state);

        assertThat(json).contains("\"label\": \"A-1 EUR\"");
        String schema = JsonSchemas.values(reference.schema(), "Account");
        assertThat(GeneratedSchemaCheck.isValid(schema, valuesOf(json))).isTrue();
    }

    /** The names of attributes that the schema of payloads allows in the values of a patch or a replace. */
    private static List<String> names(Object tree) {
        return ((List<?>) DocumentTrees.at(tree, "$defs", "attributeName", "enum")).stream().map(String::valueOf).toList();
    }

    private static String valuesOf(String stateJson) {
        Object values = DocumentTrees.at(DocumentTrees.load(stateJson), "values");
        return JsonText.render(toNode(values));
    }

    private static WrittenNode toNode(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            WrittenNode.Mapping mapping = new WrittenNode.Mapping();
            map.forEach((key, entry) -> mapping.put(String.valueOf(key), toNode(entry)));
            return mapping;
        }
        if (value instanceof String text) {
            return new WrittenNode.Text(text);
        }
        return new WrittenNode.Plain(String.valueOf(value));
    }

    // ------------------------------------------------------------------ reading and the store

    @Test
    void thePayloadReaderReadsTheValueOfADerivedAttributeLikeAnyOther() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(false));
        String payload = payload(createAccount(", \"label\": \"mine\""));

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema());

        assertThat(read.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Create.class,
                create -> assertThat(create.values()).containsEntry("label", Value.of("mine")));
    }

    @Test
    void aCreateThatSetsADerivedAttributeIsRejectedAtTheValueInThePayload() {
        ReferenceDomainStore reference = new ReferenceDomainStore(accountWithLabel(true));
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        String payload = payload(
                "{ \"create\": { \"temporaryId\": \"a1\", \"entityType\": \"Account\", "
                        + "\"from\": \"2026-01-01T00:00:00Z\", \"values\": { \"number\": \"A-1\", \"currency\": "
                        + "\"EUR\", \"balance\": 5, \"Label\": \"mine\" } } }",
                "{ \"relate\": { \"relationshipType\": \"HeldBy\", \"source\": \"$a1\", \"target\": \""
                        + ((EntityId) parties.temporaryIds().get(ReferenceStories.BETA)).value() + "\", "
                        + "\"from\": \"2026-01-01T00:00:00Z\" } }");
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());

        DryRunResult dryRun = reference.store.dryRun(read.transaction());
        CommitResult commit = reference.store.commit(read.transaction());

        assertThat(dryRun).isInstanceOf(DryRunResult.Rejected.class);
        List<Problem> located = read.locate(((CommitResult.Rejected) commit).problems());
        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
            assertThat(problem.path()).isEqualTo("$a1.label");
            assertThat(problem.location()).as("the key as the payload spells it")
                    .contains(locationOf(payload, "\"Label\"", "/writes/0/create/values/Label"));
        });
        assertThat(read.locate(((DryRunResult.Rejected) dryRun).problems())).isEqualTo(located);
    }
}
