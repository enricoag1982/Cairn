package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * What registers a set of types: a name and a version (capability {@code schema-evolution}).
 *
 * <p>The name must contain only ASCII letters, digits, dots, hyphens and underscores, and the version must be numbers
 * separated by dots. A registry rejects other origins with a {@link ProblemCode#INVALID_ORIGIN} problem.
 *
 * @param name    the origin's name, for example the name of a model module
 * @param version the origin's version, for example {@code 1.4.0}
 */
public record Origin(String name, String version) {

    /**
     * Rejects null components.
     */
    public Origin {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }

    @Override
    public String toString() {
        return name + " " + version;
    }
}
