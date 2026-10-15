package io.github.enricoag1982.cairn.expressions;

/**
 * A compiled check that gives no result for a version (ADR-0030, capability {@code checks}): its evaluation failed,
 * for example because the expression reads an attribute that has no value without testing for it, divides by zero, or
 * exceeds the cost limit of the language. The message says what went wrong.
 */
public final class CheckEvaluationException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message what went wrong
     */
    public CheckEvaluationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param message what went wrong
     * @param cause   the failure in the language
     */
    public CheckEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
