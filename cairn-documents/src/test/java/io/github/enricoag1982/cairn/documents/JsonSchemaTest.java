package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Documents against the published JSON Schema, and against the reader: a document that is invalid for one of the two is
 * invalid for the other, and the documents that the reader accepts are valid (capability {@code schema-documents},
 * requirement "Published JSON Schema").
 */
class JsonSchemaTest {

    private static String attribute(String properties) {
        return document("entityTypes:", "  Thing:", "    attributes:", "      value: { " + properties + " }");
    }

    private static String relationship(String properties) {
        return document("relationshipTypes:", "  Links:", "    source: A", "    target: B", "    inverseName: linkedBy",
                "    targetsPerSource: \"0..*\"", "    sourcesPerTarget: \"0..*\"", properties);
    }

    private static final String HEADER = "cairn: 1\norigin: { name: test-model, version: \"1.0.0\" }\n";

    static Stream<Arguments> validDocuments() {
        return Stream.of(
                Arguments.of("only the required keys", HEADER),
                Arguments.of("every section empty", document("traits: {}", "entityTypes: {}", "relationshipTypes: {}")),
                Arguments.of("types with all defaults", document("traits:", "  Named: {}", "entityTypes:", "  Thing: {}")),
                Arguments.of("an entity type with every property", document(
                        "entityTypes:",
                        "  Thing:",
                        "    description: A thing.",
                        "    abstract: true",
                        "    supertype: Base",
                        "    traits: [Named]",
                        "    attributes:",
                        "      code: { type: string }",
                        "    aliases: [Item]",
                        "    deprecated: true",
                        "    rules:",
                        "      - { name: a-rule, version: 2 }")),
                Arguments.of("a relationship type with every property", document(
                        "relationshipTypes:",
                        "  Links:",
                        "    description: Links.",
                        "    source: A",
                        "    target: A",
                        "    targetsPerSource: \"0..1\"",
                        "    sourcesPerTarget: \"1..*\"",
                        "    inverseName: linkedBy",
                        "    inverseAliases: [linkedFrom]",
                        "    kind: composition",
                        "    symmetric: true",
                        "    supertype: Base",
                        "    attributes:",
                        "      since: { type: date }",
                        "    aliases: [Joins]",
                        "    deprecated: true",
                        "    rules:",
                        "      - { name: a-rule, version: 1 }")),
                Arguments.of("every value type", document(
                        "entityTypes:",
                        "  Thing:",
                        "    attributes:",
                        "      a: { type: string }",
                        "      b: { type: boolean }",
                        "      c: { type: int }",
                        "      d: { type: long }",
                        "      e: { type: decimal }",
                        "      f: { type: date }",
                        "      g: { type: instant }")),
                Arguments.of("every multiplicity", document(
                        "relationshipTypes:",
                        "  A1: { source: A, target: B, inverseName: a1, targetsPerSource: \"0..1\", sourcesPerTarget: \"1..1\" }",
                        "  A2: { source: A, target: B, inverseName: a2, targetsPerSource: \"0..*\", sourcesPerTarget: \"1..*\" }")),
                Arguments.of("examples of any scalar", attribute("type: date, examples: [1, 1.5, abc, true, 2026-01-05]")),
                Arguments.of("string constraints", attribute(
                        "type: string, allowedValues: [a, b], pattern: \"[a-z]+\", minLength: 0, maxLength: 0x10")),
                Arguments.of("an empty range", attribute("type: int, range: {}")),
                Arguments.of("a range with one bound", attribute("type: int, range: { lessThan: 99999999999999999999 }")),
                Arguments.of("a range with both bounds", attribute("type: decimal, range: { greaterThan: 0, atMost: 1.5 }")),
                Arguments.of("a range of dates", attribute("type: date, range: { atLeast: 2026-01-01, lessThan: 2027-01-01 }")),
                Arguments.of("the same document as JSON", """
                        {"cairn": 1, "origin": {"name": "test-model", "version": "1"}, "entityTypes": {"Thing": {}}}
                        """));
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                // keys
                Arguments.of("an unknown key in the document", HEADER + "bogus: 1\n"),
                Arguments.of("an unknown key in the origin",
                        "cairn: 1\norigin: { name: test-model, version: \"1\", extra: 1 }\n"),
                Arguments.of("an unknown key in a trait", document("traits:", "  Named: { requird: true }")),
                Arguments.of("an unknown key in an entity type", document("entityTypes:", "  Thing: { abstrakt: true }")),
                Arguments.of("an unknown key in a relationship type", relationship("    symetric: true")),
                Arguments.of("an unknown key in an attribute", attribute("type: string, requird: true")),
                Arguments.of("an unknown key in a range", attribute("type: int, range: { atleast: 1 }")),
                Arguments.of("an unknown key in a rule reference", document(
                        "entityTypes:", "  Thing:", "    rules:", "      - { name: a-rule, version: 1, extra: 1 }")),
                // missing keys
                Arguments.of("no cairn key", "origin: { name: test-model, version: \"1\" }\n"),
                Arguments.of("no origin", "cairn: 1\n"),
                Arguments.of("an origin without a name", "cairn: 1\norigin: { version: \"1\" }\n"),
                Arguments.of("an origin without a version", "cairn: 1\norigin: { name: test-model }\n"),
                Arguments.of("an attribute without a type", attribute("required: true")),
                Arguments.of("a relationship type without a source", document(
                        "relationshipTypes:", "  Links:", "    target: B", "    inverseName: i", "    targetsPerSource: \"0..*\"",
                        "    sourcesPerTarget: \"0..*\"")),
                Arguments.of("a relationship type without a target", document(
                        "relationshipTypes:", "  Links:", "    source: A", "    inverseName: i", "    targetsPerSource: \"0..*\"",
                        "    sourcesPerTarget: \"0..*\"")),
                Arguments.of("a relationship type without an inverse name", document(
                        "relationshipTypes:", "  Links:", "    source: A", "    target: B", "    targetsPerSource: \"0..*\"",
                        "    sourcesPerTarget: \"0..*\"")),
                Arguments.of("a relationship type without targetsPerSource", document(
                        "relationshipTypes:", "  Links:", "    source: A", "    target: B", "    inverseName: i",
                        "    sourcesPerTarget: \"0..*\"")),
                Arguments.of("a relationship type without sourcesPerTarget", document(
                        "relationshipTypes:", "  Links:", "    source: A", "    target: B", "    inverseName: i",
                        "    targetsPerSource: \"0..*\"")),
                Arguments.of("a rule reference without a version", document(
                        "entityTypes:", "  Thing:", "    rules:", "      - { name: a-rule }")),
                Arguments.of("a rule reference without a name", document(
                        "entityTypes:", "  Thing:", "    rules:", "      - { version: 1 }")),
                // the format version
                Arguments.of("another format version", "cairn: 2\norigin: { name: test-model, version: \"1\" }\n"),
                Arguments.of("a format version in quotes", "cairn: \"1\"\norigin: { name: test-model, version: \"1\" }\n"),
                Arguments.of("a format version that is no integer", "cairn: 1.5\norigin: { name: test-model, version: \"1\" }\n"),
                // values of the wrong kind
                Arguments.of("a version that is a number", "cairn: 1\norigin: { name: test-model, version: 1.10 }\n"),
                Arguments.of("a version that is an integer", "cairn: 1\norigin: { name: test-model, version: 1 }\n"),
                Arguments.of("an origin name that is a boolean", "cairn: 1\norigin: { name: true, version: \"1\" }\n"),
                Arguments.of("an origin that is a string", "cairn: 1\norigin: test-model\n"),
                Arguments.of("a section that is a list", document("entityTypes: [Thing]")),
                Arguments.of("a type that is a string", document("entityTypes:", "  Thing: abstract")),
                Arguments.of("an unknown value type", attribute("type: number")),
                Arguments.of("a value type that is not a string", attribute("type: 5")),
                Arguments.of("a flag that is a string", attribute("type: string, required: \"true\"")),
                Arguments.of("a flag that is a number", attribute("type: string, unique: 1")),
                Arguments.of("a description that is a number", attribute("type: string, description: 5")),
                Arguments.of("aliases that are a string", attribute("type: string, aliases: old")),
                Arguments.of("aliases with a number", attribute("type: string, aliases: [old, 5]")),
                Arguments.of("allowed values with a number", attribute("type: string, allowedValues: [a, 5]")),
                Arguments.of("a pattern that is a number", attribute("type: string, pattern: 5")),
                Arguments.of("a length that is a string", attribute("type: string, minLength: \"3\"")),
                Arguments.of("a length that is a decimal", attribute("type: string, maxLength: 1.5")),
                Arguments.of("a length that does not fit 32 bits", attribute("type: string, maxLength: 3000000000")),
                Arguments.of("examples that are no list", attribute("type: string, examples: abc")),
                Arguments.of("an example that is a list", attribute("type: string, examples: [[a]]")),
                Arguments.of("an example that is null", attribute("type: string, examples: [null]")),
                Arguments.of("a range that is a list", attribute("type: int, range: [1, 2]")),
                Arguments.of("a bound that is a list", attribute("type: int, range: { atLeast: [1] }")),
                Arguments.of("a bound that is null", attribute("type: int, range: { atLeast: null }")),
                Arguments.of("a lower bound written in both forms", attribute("type: int, range: { atLeast: 1, greaterThan: 0 }")),
                Arguments.of("an upper bound written in both forms", attribute("type: int, range: { atMost: 5, lessThan: 6 }")),
                Arguments.of("an unknown multiplicity", relationship("    kind: association").replace("\"0..*\"", "\"2..3\"")),
                Arguments.of("a multiplicity that is a number", relationship("    kind: association")
                        .replace("targetsPerSource: \"0..*\"", "targetsPerSource: 1")),
                Arguments.of("a multiplicity in another form", relationship("    kind: association")
                        .replace("targetsPerSource: \"0..*\"", "targetsPerSource: \"0..n\"")),
                Arguments.of("an unknown kind", relationship("    kind: aggregation")),
                Arguments.of("rules that are a mapping", document("entityTypes:", "  Thing:", "    rules: { name: a-rule }")),
                Arguments.of("a rule version that is a string", document(
                        "entityTypes:", "  Thing:", "    rules:", "      - { name: a-rule, version: \"1\" }")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validDocuments")
    void aDocumentThatTheReaderAcceptsIsValid(String name, String text) {
        assertThat(SchemaDocuments.read(text)).as("reading").isInstanceOf(DocumentReadResult.Read.class);
        assertThat(JsonSchemas.errors(text)).as("errors of the JSON Schema").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void aDocumentWithAProblemIsInvalidAndTheReaderRejectsIt(String name, String text) {
        assertThat(SchemaDocuments.read(text)).as("reading").isInstanceOf(DocumentReadResult.Invalid.class);
        assertThat(JsonSchemas.errors(text)).as("errors of the JSON Schema").isNotEmpty();
    }

    @Test
    void theErrorNamesTheUnknownKey() {
        assertThat(JsonSchemas.errors(attribute("type: string, requird: true")))
                .anySatisfy(error -> assertThat(error.getMessage()).contains("requird"));
    }

    @Test
    void theErrorNamesTheMissingKey() {
        assertThat(JsonSchemas.errors("cairn: 1\norigin: { name: test-model }\n"))
                .anySatisfy(error -> assertThat(error.getMessage()).contains("version"));
    }

    @Test
    void theWrittenReferenceModelIsValidAsYamlAndAsJson() {
        String yaml = SchemaDocuments.writeYaml(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());
        String json = SchemaDocuments.writeJson(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        assertThat(JsonSchemas.errors(yaml)).isEmpty();
        assertThat(JsonSchemas.errors(json)).isEmpty();
        assertThat(JsonSchemas.errorsOfJson(json)).isEmpty();
    }

    @Test
    void theReferenceDocumentIsValidAsItIsAndAsJson() throws IOException {
        SchemaDocument document = Documents.valid(Documents.referenceModelText());

        assertThat(JsonSchemas.errors(Documents.referenceModelText())).isEmpty();
        assertThat(JsonSchemas.errorsOfJson(SchemaDocuments.writeJson(document.origin(), document.definition()))).isEmpty();
    }

    @Test
    void theJsonSchemaIsPublishedInTheModule() {
        assertThat(SchemaDocuments.jsonSchema()).startsWith("{").contains("\"$id\": \"urn:cairn:schema-document:1\"");
        assertThat(SchemaDocuments.jsonSchema()).isEqualTo(SchemaDocuments.jsonSchema());
    }
}
