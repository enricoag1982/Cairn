package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Helpers for the tests that read schema documents. */
final class Documents {

    /** The schema document of the reference domain, which the tests read as it is in {@code docs/reference}. */
    static final Path REFERENCE_MODEL = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-model.cairn.yaml");

    private Documents() {
    }

    static String referenceModelText() throws IOException {
        return Files.readString(REFERENCE_MODEL);
    }

    /** Reads a document that must have no problems. */
    static SchemaDocument valid(String text) {
        DocumentReadResult result = SchemaDocuments.read(text);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(DocumentReadResult.Read.class);
        return ((DocumentReadResult.Read) result).document();
    }

    /** Reads a document that must have problems, and returns them. */
    static List<Problem> problems(String text) {
        DocumentReadResult result = SchemaDocuments.read(text);
        assertThat(result).as("result of reading:%n%s", text).isInstanceOf(DocumentReadResult.Invalid.class);
        List<Problem> problems = ((DocumentReadResult.Invalid) result).problems();
        assertThat(problems).as("every problem of a document is located").allSatisfy(problem ->
                assertThat(problem.location()).as(problem.toString()).isPresent());
        return problems;
    }

    /** Reads a document that must have exactly one problem, and returns it. */
    static Problem problem(String text) {
        List<Problem> problems = problems(text);
        assertThat(problems).as("problems of:%n%s", text).hasSize(1);
        return problems.getFirst();
    }

    /** A document with an origin and the given lines after it. */
    static String document(String... lines) {
        return "cairn: 1\norigin: { name: test-model, version: \"1.0.0\" }\n" + String.join("\n", lines) + "\n";
    }

    /** Asserts the code, path and location of a problem. */
    static void assertProblem(Problem problem, ProblemCode code, String path, String pointer, int line, int column) {
        assertThat(problem.code()).as("code of %s", problem).isEqualTo(code);
        assertThat(problem.path()).as("path of %s", problem).isEqualTo(path);
        assertThat(problem.location()).as("location of %s", problem)
                .contains(new DocumentLocation(pointer, line, column));
    }
}
