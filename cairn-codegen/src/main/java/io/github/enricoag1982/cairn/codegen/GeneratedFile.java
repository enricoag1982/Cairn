package io.github.enricoag1982.cairn.codegen;

import java.util.Objects;

/**
 * A file of generated Java source (capability {@code java-types}).
 *
 * @param path the path relative to the output directory, with {@code /} as the separator, such as
 *             {@code com/example/types/Person.java}
 * @param text the source, in UTF-8, with line breaks as {@code \n} and a line break at the end
 */
public record GeneratedFile(String path, String text) {

    /**
     * Rejects null components.
     */
    public GeneratedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(text, "text");
    }
}
