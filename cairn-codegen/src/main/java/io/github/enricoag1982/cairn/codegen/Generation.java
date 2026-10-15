package io.github.enricoag1982.cairn.codegen;

import java.util.List;

/**
 * The outcome of {@link JavaTypes#generate} (capability {@code java-types}): the generated files, or every problem of
 * the documents.
 */
public sealed interface Generation {

    /**
     * The documents have no problems.
     *
     * @param files the generated files, sorted by path
     */
    record Generated(List<GeneratedFile> files) implements Generation {
        /**
         * Copies the file list.
         */
        public Generated {
            files = List.copyOf(files);
        }
    }

    /**
     * The documents have problems, so nothing is generated.
     *
     * @param problems every problem found, each located in its document, in the order of the documents
     */
    record Failed(List<DocumentProblem> problems) implements Generation {
        /**
         * Copies the problem list.
         */
        public Failed {
            problems = List.copyOf(problems);
        }
    }
}
