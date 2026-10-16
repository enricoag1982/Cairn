package io.github.enricoag1982.cairn.expressions;

/**
 * A compiled check or derivation that gives no result for a version (ADR-0030 and ADR-0031, capabilities {@code checks}
 * and {@code derived-attributes}): its evaluation failed, for example because the expression reads an attribute that
 * has no value without testing for it, divides by zero, or exceeds the cost limit of the language; or, for a derivation,
 * its result does not fit the attribute's value type. The message says what went wrong.
 */
public final class ExpressionEvaluationException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message what went wrong
     */
    public ExpressionEvaluationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param message what went wrong
     * @param cause   the failure in the language
     */
    public ExpressionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
