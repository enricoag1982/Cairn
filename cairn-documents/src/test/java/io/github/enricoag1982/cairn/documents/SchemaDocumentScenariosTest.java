package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.problems;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
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
                            Bound.exclusive(Literal.of(new BigDecimal("0"))),
                            Bound.inclusive(Literal.of(new BigDecimal("100")))));
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
    @DisplayName("Scenario: Locating an attribute problem")
    void locatingAnAttributeProblem() throws IOException {
        String text = Documents.referenceModelText().replace("balance: { type: decimal, required: true }",
                "balance: { type: decimal, required: true, pattern: \"[0-9]+\" }");
        SchemaDocument document = valid(text);

        List<Problem> problems = document.locate(registry.validate(document.origin(), document.definition()));

        assertThat(problems).singleElement().satisfies(problem ->
                assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Account/attributes/balance"));
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
        assertThat(JsonSchemas.errors(Documents.referenceModelText())).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A misspelled key is invalid")
    void aMisspelledKeyIsInvalid() throws IOException {
        String text = Documents.referenceModelText().replace("number: { type: string, required: true, unique: true }",
                "number: { type: string, requird: true, unique: true }");

        assertThat(JsonSchemas.errors(text)).isNotEmpty()
                .anySatisfy(error -> assertThat(error.getMessage()).contains("requird"));
    }
}
