package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.problems;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code schema-documents} spec, one test each: reading, strict reading, locating problems,
 * writing and the published JSON Schema.
 */
class SchemaDocumentScenariosTest {

    private final SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.system(), ReferenceModel.rules());

    // ------------------------------------------------------------------ schema document format

    @Test
    @DisplayName("Scenario: Reading the reference model")
    void readingTheReferenceModel() throws IOException {
        SchemaDocument document = valid(Documents.referenceModelText());

        assertThat(document.origin()).isEqualTo(new Origin("reference-model", "1.0.0"));
        assertThat(document.definition()).isEqualTo(ReferenceModel.definition());
    }

    @Test
    @DisplayName("Scenario: Reading a document written as JSON")
    void readingADocumentWrittenAsJson() throws IOException {
        SchemaDocument fromYaml = valid(Documents.referenceModelText());
        String json = SchemaDocuments.writeJson(fromYaml.origin(), fromYaml.definition());

        SchemaDocument fromJson = valid(json);

        assertThat(json).startsWith("{\n  \"cairn\": 1,\n");
        assertThat(fromJson.origin()).isEqualTo(fromYaml.origin());
        assertThat(fromJson.definition()).isEqualTo(fromYaml.definition());
    }

    @Test
    @DisplayName("Scenario: Unsupported format version")
    void unsupportedFormatVersion() throws IOException {
        String text = Documents.referenceModelText().replace("cairn: 1", "cairn: 2");

        Problem problem = problem(text);

        assertProblem(problem, ProblemCode.UNSUPPORTED_FORMAT_VERSION, "@document", "/cairn", 3, 1);
    }

    @Test
    @DisplayName("Scenario: Reading a check")
    void readingACheck() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    checks: { balance-not-negative: { expression: \"self.balance >= 0.0\", "
                        + "message: \"A balance cannot be negative.\" } }"));

        assertThat(document.definition().entityTypes()).singleElement().satisfies(account ->
                assertThat(account.checks()).containsExactly(new Check("balance-not-negative", "self.balance >= 0.0",
                        Optional.of("A balance cannot be negative."))));
    }

    @Test
    @DisplayName("Scenario: Reading a derived attribute")
    void readingADerivedAttribute() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      number: { type: string, required: true }",
                "      currency: { type: string, required: true }",
                "      label: { type: string, required: true, derived: \"self.number + ' ' + self.currency\" }"));

        assertThat(document.definition().entityTypes()).singleElement().satisfies(account ->
                assertThat(account.attributes()).filteredOn(attribute -> attribute.name().equals("label"))
                        .singleElement().satisfies(label -> {
                            assertThat(label.derived()).hasValue("self.number + ' ' + self.currency");
                            assertThat(label.valueType()).isEqualTo(ValueType.STRING);
                            assertThat(label.required()).isTrue();
                        }));
    }

    @Test
    @DisplayName("Scenario: Reading a relationship type that outlives its targets")
    void readingARelationshipTypeThatOutlivesItsTargets() {
        SchemaDocument document = valid(document(
                "relationshipTypes:",
                "  OpenedBy:",
                "    source: Account",
                "    target: Person",
                "    targetsPerSource: \"0..1\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: opened",
                "    outlivesTarget: true",
                "  HeldBy:",
                "    source: Account",
                "    target: Party",
                "    targetsPerSource: \"1..1\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: holds"));

        assertThat(document.definition().relationshipTypes()).extracting(RelationshipTypeDefinition::name,
                RelationshipTypeDefinition::outlivesTarget).containsExactly(tuple("OpenedBy", true),
                tuple("HeldBy", false));
    }

    @Test
    @DisplayName("Scenario: Reading a classified attribute")
    void readingAClassifiedAttribute() {
        SchemaDocument document = valid(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      number: { type: string, required: true }",
                "      balance: { type: decimal, required: true, classification: finance }"));

        assertThat(document.definition().entityTypes()).singleElement().satisfies(account ->
                assertThat(account.attributes()).extracting(AttributeDefinition::name,
                        AttributeDefinition::classification).containsExactly(
                                tuple("number", Optional.empty()),
                                tuple("balance", Optional.of("finance"))));
    }

    @Test
    @DisplayName("Scenario: Range bounds")
    void rangeBounds() {
        SchemaDocument document = valid(document(
                "relationshipTypes:",
                "  Owns:",
                "    source: Organization",
                "    target: Party",
                "    targetsPerSource: \"0..*\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: ownedBy",
                "    attributes:",
                "      share: { type: decimal, required: true, range: { greaterThan: 0, atMost: 100 } }"));

        assertThat(document.definition().relationshipTypes()).singleElement().satisfies(owns ->
                assertThat(owns.attributes()).singleElement().satisfies(share -> {
                    assertThat(share.name()).isEqualTo("share");
                    assertThat(share.range()).contains(Range.between(
                            Bound.exclusive(Value.of(new BigDecimal("0"))),
                            Bound.inclusive(Value.of(new BigDecimal("100")))));
                }));
    }

    // ------------------------------------------------------------------ strict reading

    @Test
    @DisplayName("Scenario: Misspelled key")
    void misspelledKey() throws IOException {
        String text = Documents.referenceModelText().replace("number: { type: string, required: true, unique: true }",
                "number: { type: string, requird: true, unique: true }");

        Problem problem = problem(text);

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("required"));
    }

    @Test
    @DisplayName("Scenario: Anchors are rejected")
    void anchorsAreRejected() {
        Problem problem = problem(document(
                "entityTypes:",
                "  Address: &address",
                "    attributes:",
                "      street: { type: string, required: true }",
                "  Branch: *address"));

        assertProblem(problem, ProblemCode.UNSUPPORTED_YAML_FEATURE, "Address", "/entityTypes/Address", 4, 12);
    }

    @Test
    @DisplayName("Scenario: Duplicate key")
    void duplicateKey() {
        Problem problem = problem(document(
                "entityTypes:",
                "  Address:",
                "    attributes:",
                "      street: { type: string, required: true }",
                "  Address:",
                "    attributes:",
                "      city: { type: string, required: true }"));

        assertProblem(problem, ProblemCode.DUPLICATE_KEY, "Address", "/entityTypes/Address", 7, 3);
    }

    @Test
    @DisplayName("Scenario: Syntax error")
    void syntaxError() {
        List<Problem> problems = problems(document(
                "entityTypes:",
                "  CustomerRole:",
                "    attributes:",
                "      segment: { type: string, allowedValues: [Corporate, Retail }"));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DOCUMENT_SYNTAX);
            assertThat(problem.location()).hasValueSatisfying(location -> {
                assertThat(location.line()).isPositive();
                assertThat(location.column()).isPositive();
            });
        });
    }

    // ------------------------------------------------------------------ problems are located

    @Test
    @DisplayName("Scenario: Locating a registration problem")
    void locatingARegistrationProblem() throws IOException {
        String text = Documents.referenceModelText().replace("  Organization:\n    supertype: Party",
                "  Organization:\n    supertype: Prty");
        SchemaDocument document = valid(text);

        RegistrationResult result = registry.register(document.origin(), document.definition());

        assertThat(result).isInstanceOf(RegistrationResult.Rejected.class);
        List<Problem> problems = document.locate(((RegistrationResult.Rejected) result).problems());
        int line = text.substring(0, text.indexOf("  Organization:")).split("\n", -1).length;
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("Organization");
            assertThat(problem.location()).contains(new DocumentLocation("/entityTypes/Organization", line, 3));
        });
    }

    @Test
    @DisplayName("Scenario: Problem outside every element")
    void problemOutsideEveryElement() {
        Problem problem = problem(document(
                "entityType:",
                "  Address:",
                "    attributes:",
                "      street: { type: string, required: true }"));

        assertProblem(problem, ProblemCode.UNKNOWN_KEY, "@document", "/entityType", 3, 1);
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("entityTypes"));
    }

    @Test
    @DisplayName("Scenario: Locating an attribute problem")
    void locatingAnAttributeProblem() throws IOException {
        String text = Documents.referenceModelText().replace("balance: { type: decimal, required: true }",
                "balance: { type: decimal, required: true, pattern: \"[0-9]+\" }");
        SchemaDocument document = valid(text);

        List<Problem> problems = document.locate(registry.validate(document.origin(), document.definition()));

        assertThat(problems).singleElement().satisfies(problem ->
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Account/attributes/balance"));
    }

    @Test
    @DisplayName("Scenario: Locating a check problem")
    void locatingACheckProblem() throws IOException {
        String text = Documents.referenceModelText().replace("  Account:\n",
                "  Account:\n    checks:\n      balance-not-negative:\n        expression: self.amount >= 0.0\n");
        SchemaDocument document = valid(text);

        List<Problem> problems = document.locate(registry.validate(document.origin(), document.definition()));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
            assertThat(problem.location().orElseThrow().pointer())
                    .isEqualTo("/entityTypes/Account/checks/balance-not-negative/expression");
        });
    }

    // ------------------------------------------------------------------ writing

    @Test
    @DisplayName("Scenario: Round trip of the reference model")
    void roundTripOfTheReferenceModel() {
        Origin origin = ReferenceModel.origin("1.0.0");
        SchemaDefinition definition = ReferenceModel.definition();

        SchemaDocument fromYaml = valid(SchemaDocuments.writeYaml(origin, definition));
        SchemaDocument fromJson = valid(SchemaDocuments.writeJson(origin, definition));

        assertThat(fromYaml.origin()).isEqualTo(origin);
        assertThat(fromYaml.definition()).isEqualTo(definition);
        assertThat(fromJson.origin()).isEqualTo(origin);
        assertThat(fromJson.definition()).isEqualTo(definition);
    }

    @Test
    @DisplayName("Scenario: Widened numbers come back in the attribute's type")
    void widenedNumbersComeBackInTheAttributesType() {
        Origin origin = new Origin("test-model", "1.0.0");
        SchemaDefinition written = account(Value.of(0));
        SchemaDefinition expected = account(Value.of(new BigDecimal("0")));

        for (String text : List.of(SchemaDocuments.writeYaml(origin, written), SchemaDocuments.writeJson(origin, written))) {
            SchemaDocument document = valid(text);

            assertThat(document.definition().entityTypes()).singleElement().satisfies(account ->
                    assertThat(account.attributes().getLast().range())
                            .contains(Range.atLeast(Bound.inclusive(Value.of(new BigDecimal("0"))))));
            assertThat(document.definition()).isEqualTo(expected);
            assertThat(document.definition()).isNotEqualTo(written);
        }
    }

    /** An entity type Account whose decimal attribute balance has a range from a bound, included. */
    private static SchemaDefinition account(Value lowerBound) {
        return SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .description("Holds a balance.")
                        .attribute("currency", ValueType.STRING, a -> a.required().pattern("[A-Z]{3}"))
                        .attribute("balance", ValueType.DECIMAL, a -> a.required()
                                .range(Range.atLeast(Bound.inclusive(lowerBound)))))
                .build();
    }

    @Test
    @DisplayName("Scenario: Defaults are left out")
    void defaultsAreLeftOut() {
        Origin origin = new Origin("test-model", "1.0.0");
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Thing", e -> e.attribute("note", ValueType.STRING))
                .build();

        String yaml = SchemaDocuments.writeYaml(origin, definition);
        String json = SchemaDocuments.writeJson(origin, definition);

        assertThat(yaml).isEqualTo("""
                cairn: 1
                origin:
                  name: test-model
                  version: "1.0.0"
                entityTypes:
                  Thing:
                    attributes:
                      note:
                        type: string
                """);
        assertThat(DocumentTrees.at(DocumentTrees.load(json), "entityTypes", "Thing", "attributes", "note"))
                .isEqualTo(Map.of("type", "string"));
        assertThat(DocumentTrees.keysAt(DocumentTrees.load(json), "entityTypes", "Thing")).containsExactly("attributes");
    }

    // ------------------------------------------------------------------ published JSON Schema

    @Test
    @DisplayName("Scenario: The reference document is valid")
    void theReferenceDocumentIsValid() throws IOException {
        assertThat(PublishedSchemaCheck.errors(Documents.referenceModelText())).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A misspelled key is invalid")
    void aMisspelledKeyIsInvalid() throws IOException {
        String text = Documents.referenceModelText().replace("number: { type: string, required: true, unique: true }",
                "number: { type: string, requird: true, unique: true }");

        assertThat(PublishedSchemaCheck.errors(text)).isNotEmpty()
                .anySatisfy(error -> assertThat(error.getMessage()).contains("requird"));
    }
}
