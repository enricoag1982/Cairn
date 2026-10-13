package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * One reason a schema definition or registration is rejected (capabilities {@code type-model} and {@code schema-evolution}).
 *
 * <p>The code, path and message are always present. A problem can also say what was expected, what was found and how
 * to fix it. These details are text for people and agents to read: anything a program needs to act on is in the
 * code and the path.
 *
 * @param code     a stable code
 * @param path     the element concerned: a type name, or a type name and an attribute, inverse name or rule name joined by a
 *                 dot; {@code @origin} for problems with the origin itself
 * @param message  a readable explanation
 * @param expected what would have been accepted, for example the form a name must have
 * @param found    what the definition contains instead, for example the offending name
 * @param hint     how to fix the problem, for example the closest name that exists
 */
public record Problem(
        ProblemCode code,
        String path,
        String message,
        Optional<String> expected,
        Optional<String> found,
        Optional<String> hint) {

    /**
     * Rejects null components.
     */
    public Problem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(found, "found");
        Objects.requireNonNull(hint, "hint");
    }

    /**
     * Creates a problem without expected value, found value or hint.
     *
     * @param code    a stable code
     * @param path    the element concerned
     * @param message a readable explanation
     */
    public Problem(ProblemCode code, String path, String message) {
        this(code, path, message, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public String toString() {
        return code + " at " + path + ": " + message;
    }
}
