package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link SchemaDocuments#read(String)} (capability {@code schema-documents}): the document, or every
 * problem found in the text.
 */
public sealed interface DocumentReadResult {

    /**
     * The text is a schema document without problems.
     *
     * @param document the origin, the schema definition and the locations of its elements
     */
    record Read(SchemaDocument document) implements DocumentReadResult {
        /**
         * Rejects a null document.
         */
        public Read {
            Objects.requireNonNull(document, "document");
        }
    }

    /**
     * The text has problems, so there is no definition. Every problem is located in the text.
     *
     * @param problems every problem found, in the order of the text; a syntax error or a format version other than 1 is
     *                 the only problem, because it stops reading
     */
    record Invalid(List<Problem> problems) implements DocumentReadResult {
        /**
         * Copies the problem list.
         */
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
