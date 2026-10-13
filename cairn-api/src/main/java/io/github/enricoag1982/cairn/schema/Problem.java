package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * One reason a schema definition or registration is rejected (capabilities {@code type-model} and {@code schema-evolution}).
 *
 * @param code    a stable code
 * @param path    the element concerned: a type name, or a type name and an attribute or inverse name joined by a
 *                dot; {@code @origin} for problems with the origin itself
 * @param message a readable explanation
 */
public record Problem(ProblemCode code, String path, String message) {

    /**
     * Rejects null components.
     */
    public Problem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return code + " at " + path + ": " + message;
    }
}
