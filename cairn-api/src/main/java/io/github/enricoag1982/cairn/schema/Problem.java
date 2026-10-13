package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * One reason a schema definition or registration is rejected (capabilities {@code type-model} and {@code schema-evolution}),
 * a schema document (capability {@code schema-documents}) or a transaction payload (capability {@code json-payloads})
 * cannot be read, or a data transaction is rejected (capabilities {@code transactions}, {@code entity-versions},
 * {@code entity-rules} and {@code relationships}), or a request of an AI agent is refused (capability
 * {@code agent-tools}).
 *
 * <p>The code, path and message are always present. A problem can also say what was expected, what was found and how
 * to fix it. These details are text for people and agents to read: anything a program needs to act on is in the
 * code and the path.
 *
 * <p>The engine does not know about documents or payloads, so the problems it reports have no location. A problem about
 * a schema document or a transaction payload, and a problem about the definition or transaction read from one after
 * {@linkplain #at(DocumentLocation) it has been located}, carry the place in the text.
 *
 * <p>A problem that a rule reports ({@link ProblemCode#RULE_VIOLATION}) also carries the rule's own code, qualified by
 * the rule's name, such as {@code lei-checksum/invalid-check-digits}.
 *
 * @param code     a stable code
 * @param path     the element concerned: a type name, or a type name and an attribute, inverse name or rule name joined by a
 *                 dot; {@code @origin} for problems with the origin itself; {@code @document} for problems with a
 *                 schema document as a whole; {@code @transaction} for problems with a transaction payload as a whole,
 *                 {@code @transaction.<key>}, such as {@code @transaction.principal}, for problems with one of its
 *                 top-level keys, and {@code @write.<n>}, with the position {@code n} counted from 0, for problems
 *                 with a write of one, or {@code @write.<n>.<attribute>} for problems with one of its attributes;
 *                 {@code @arguments} for problems with the arguments of an agent tool call as a whole, such as
 *                 arguments that are not a JSON object, and {@code @arguments.<name>} for problems with one
 *                 argument of the call
 * @param message  a readable explanation
 * @param expected what would have been accepted, for example the form a name must have
 * @param found    what the definition contains instead, for example the offending name
 * @param hint     how to fix the problem, for example the closest name that exists
 * @param location where the problem is in the schema document or transaction payload it came from, if it is located
 * @param ruleCode the code a rule reported, qualified by the rule's name and separated from it by a slash, for example
 *                 {@code lei-checksum/invalid-check-digits}; empty unless the problem comes from a rule
 */
public record Problem(
        ProblemCode code,
        String path,
        String message,
        Optional<String> expected,
        Optional<String> found,
        Optional<String> hint,
        Optional<DocumentLocation> location,
        Optional<String> ruleCode) {

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
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(ruleCode, "ruleCode");
    }

    /**
     * Creates a problem that does not come from a rule.
     *
     * @param code     a stable code
     * @param path     the element concerned
     * @param message  a readable explanation
     * @param expected what would have been accepted
     * @param found    what the definition contains instead
     * @param hint     how to fix the problem
     * @param location where the problem is in the schema document or transaction payload it came from, if it is located
     */
    public Problem(
            ProblemCode code,
            String path,
            String message,
            Optional<String> expected,
            Optional<String> found,
            Optional<String> hint,
            Optional<DocumentLocation> location) {
        this(code, path, message, expected, found, hint, location, Optional.empty());
    }

    /**
     * Creates a problem without a location.
     *
     * @param code     a stable code
     * @param path     the element concerned
     * @param message  a readable explanation
     * @param expected what would have been accepted
     * @param found    what the definition contains instead
     * @param hint     how to fix the problem
     */
    public Problem(
            ProblemCode code,
            String path,
            String message,
            Optional<String> expected,
            Optional<String> found,
            Optional<String> hint) {
        this(code, path, message, expected, found, hint, Optional.empty(), Optional.empty());
    }

    /**
     * Creates a problem without expected value, found value or hint.
     *
     * @param code    a stable code
     * @param path    the element concerned
     * @param message a readable explanation
     */
    public Problem(ProblemCode code, String path, String message) {
        this(code, path, message, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /**
     * Returns a copy of this problem that is located in a schema document or a transaction payload.
     *
     * @param location where the problem is in the text
     * @return the located copy
     */
    public Problem at(DocumentLocation location) {
        return new Problem(code, path, message, expected, found, hint,
                Optional.of(Objects.requireNonNull(location, "location")), ruleCode);
    }

    @Override
    public String toString() {
        return code + ruleCode.map(rule -> " (" + rule + ")").orElse("") + " at " + path + ": " + message;
    }
}
