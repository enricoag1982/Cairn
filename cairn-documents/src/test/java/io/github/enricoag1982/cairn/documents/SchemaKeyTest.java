package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static io.github.enricoag1982.cairn.schema.ProblemCode.INVALID_VALUE;
import static io.github.enricoag1982.cairn.schema.ProblemCode.UNKNOWN_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Problem;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The top-level key {@code $schema}, which names the published JSON Schema for editors (design D6, D7, D8). */
class SchemaKeyTest {

    private static final String HEADER = "cairn: 1\norigin: { name: test-model, version: \"1.0.0\" }\n";

    @Test
    void theKeyIsAcceptedAndIgnored() {
        String types = "entityTypes:\n  Party:\n    attributes:\n      name: { type: string }\n";

        SchemaDocument without = valid(HEADER + types);

        for (String text : new String[] {
                "$schema: \"urn:cairn:schema-document:1\"\n" + HEADER + types,
                HEADER + "$schema: https://example.org/cairn/schema-document-1.schema.json\n" + types,
                "{\"$schema\": \"urn:cairn:schema-document:1\", \"cairn\": 1, \"origin\": {\"name\": \"test-model\", "
                        + "\"version\": \"1.0.0\"}, \"entityTypes\": {\"Party\": {\"attributes\": {\"name\": "
                        + "{\"type\": \"string\"}}}}}\n"}) {
            SchemaDocument with = valid(text);

            assertThat(with.origin()).as(text).isEqualTo(without.origin());
            assertThat(with.definition()).as(text).isEqualTo(without.definition());
        }
    }

    @Test
    void theKeyMayBeAnyString() {
        assertThat(SchemaDocuments.read("$schema: \"\"\n" + HEADER)).isInstanceOf(DocumentReadResult.Read.class);
        assertThat(SchemaDocuments.read("$schema: not a url at all\n" + HEADER)).isInstanceOf(DocumentReadResult.Read.class);
    }

    @Test
    void theKeyMustBeAString() {
        Problem number = problem("$schema: 5\n" + HEADER);
        assertProblem(number, INVALID_VALUE, "@document", "/$schema", 1, 10);
        assertThat(number.expected()).contains("a string");
        assertThat(number.found()).contains("the number 5");

        assertProblem(problem("$schema:\n" + HEADER), INVALID_VALUE, "@document", "/$schema", 1, 9);
        assertProblem(problem("$schema: [a]\n" + HEADER), INVALID_VALUE, "@document", "/$schema", 1, 10);
        assertProblem(problem("$schema: true\n" + HEADER), INVALID_VALUE, "@document", "/$schema", 1, 10);
    }

    @Test
    void theKeyIsNotWrittenAndNotKept() {
        SchemaDocument document = valid("$schema: \"urn:cairn:schema-document:1\"\n" + HEADER);

        assertThat(SchemaDocuments.writeYaml(document.origin(), document.definition())).doesNotContain("schema");
        assertThat(SchemaDocuments.writeJson(document.origin(), document.definition())).doesNotContain("schema");
    }

    @Test
    void aMisspelledKeyNextToTheSchemaKeyStillHasItsHint() {
        Problem problem = problem("$schema: \"urn:cairn:schema-document:1\"\n" + HEADER + "entityType: {}\n");

        assertProblem(problem, UNKNOWN_KEY, "@document", "/entityType", 4, 1);
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("entityTypes"));
    }

    @Test
    void theSchemaKeyIsValidAgainstThePublishedJsonSchema() throws IOException {
        String text = "$schema: \"urn:cairn:schema-document:1\"\n" + Documents.referenceModelText();

        assertThat(SchemaDocuments.read(text)).isInstanceOf(DocumentReadResult.Read.class);
        assertThat(PublishedSchemaCheck.errors(text)).isEmpty();
        assertThat(PublishedSchemaCheck.errors(document("entityTypes: {}"))).isEmpty();
    }
}
