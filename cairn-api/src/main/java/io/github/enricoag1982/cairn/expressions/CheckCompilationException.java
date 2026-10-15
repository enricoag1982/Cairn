package io.github.enricoag1982.cairn.expressions;

import java.util.OptionalInt;

/**
 * An expression that an {@link ExpressionLanguage} cannot compile (ADR-0030, capability {@code checks}): it is not
 * valid in the language, it reads an attribute that the type does not have, or its result cannot be a boolean.
 *
 * <p>The exception can say where in the expression the fault is. A line and a column are counted from 1, and the
 * column counts characters of the line, as a person reads them.
 */
public final class CheckCompilationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The line in the expression, from 1; 0 if it is not known. */
    private final int line;

    /** The column in the line, from 1; 0 if it is not known. */
    private final int column;

    /**
     * Creates an exception that does not know where in the expression the fault is.
     *
     * @param message what is wrong with the expression
     */
    public CheckCompilationException(String message) {
        super(message);
        this.line = 0;
        this.column = 0;
    }

    /**
     * Creates an exception that knows where in the expression the fault is.
     *
     * @param message what is wrong with the expression
     * @param line    the line in the expression, from 1
     * @param column  the column in the line, from 1
     * @throws IllegalArgumentException if the line or the column is below 1
     */
    public CheckCompilationException(String message, int line, int column) {
        super(message);
        if (line < 1) {
            throw new IllegalArgumentException("line must be at least 1: " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1: " + column);
        }
        this.line = line;
        this.column = column;
    }

    /**
     * The line in the expression where the fault is.
     *
     * @return the line, from 1, if it is known
     */
    public OptionalInt line() {
        return line == 0 ? OptionalInt.empty() : OptionalInt.of(line);
    }

    /**
     * The column in the line where the fault is.
     *
     * @return the column, from 1, if it is known
     */
    public OptionalInt column() {
        return column == 0 ? OptionalInt.empty() : OptionalInt.of(column);
    }
}
