package io.github.enricoag1982.cairn.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * A check that an entity type or relationship type declares (capability {@code checks}, ADR-0030): an expression in the
 * Common Expression Language (CEL) over one version of a value of the type, which every such version must make true.
 *
 * <p>The expression sees one variable, {@code self}, which maps the names of the attributes of the version that have a
 * value to those values. A check applies to the type that declares it and to its subtypes.
 *
 * <p>The name must consist of lowercase ASCII letters, digits and hyphens, start with a letter and have at most 128
 * characters, as the name of a rule does, and the expression must not be blank and must have at most 4,000
 * characters. Nothing is checked when a check is built, apart from a missing name, expression or message; a
 * {@link SchemaRegistry} reports a malformed name as {@link ProblemCode#INVALID_CHECK_NAME}, and a blank or too long
 * expression, like one that does not compile, as {@link ProblemCode#INVALID_CHECK}.
 *
 * @param name       the check's name, for example {@code balance-not-negative}
 * @param expression the CEL expression, for example {@code self.balance >= 0.0}
 * @param message    what to tell whoever breaks the check, if given; a problem names the expression otherwise
 */
public record Check(String name, String expression, Optional<String> message) {

    /** The most characters that an expression may have. */
    public static final int MAXIMUM_EXPRESSION_LENGTH = 4_000;

    /**
     * Rejects null components.
     */
    public Check {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Creates a check without a message.
     *
     * @param name       the check's name
     * @param expression the CEL expression
     */
    public Check(String name, String expression) {
        this(name, expression, Optional.empty());
    }
}
