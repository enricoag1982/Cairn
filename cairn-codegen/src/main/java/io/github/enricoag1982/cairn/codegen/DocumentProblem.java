package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A problem in a schema document that the generator was given (capability {@code java-types}).
 *
 * @param document the document, as the caller named it
 * @param problem  the problem, located in the document
 */
public record DocumentProblem(Path document, Problem problem) {

    /**
     * Rejects null components.
     */
    public DocumentProblem {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(problem, "problem");
    }

    /**
     * Writes the problem on one line, in the form {@code <file>:<line>:<column>: <code> <path>: <message>}, which
     * editors and build tools recognize. A problem without a location, which the generator does not report, has no line
     * and column.
     *
     * @return the line
     */
    public String format() {
        String place = problem.location().map(DocumentLocation::line).map(line -> ":" + line).orElse("")
                + problem.location().map(DocumentLocation::column).map(column -> ":" + column).orElse("");
        return document + place + ": " + problem.code() + " " + problem.path() + ": " + problem.message();
    }

    @Override
    public String toString() {
        return format();
    }
}
