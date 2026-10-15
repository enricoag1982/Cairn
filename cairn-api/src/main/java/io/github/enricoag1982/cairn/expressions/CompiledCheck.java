package io.github.enricoag1982.cairn.expressions;

import io.github.enricoag1982.cairn.schema.Value;
import java.util.Map;

/**
 * The expression of a check, compiled by an {@link ExpressionLanguage} for the attributes of one type (ADR-0030,
 * capability {@code checks}).
 *
 * <p>A compiled check is immutable and thread-safe. Evaluating it reads nothing but the values it is given and has no
 * side effects.
 */
public interface CompiledCheck {

    /**
     * Evaluates the check for one version.
     *
     * @param values the values of the version that has them, by attribute name; an attribute without a value is
     *               absent
     * @return {@code true} if the version satisfies the check
     * @throws CheckEvaluationException if the evaluation gives no result, for example because the expression reads an
     *                                  attribute that has no value, or exceeds the cost limit of the language
     */
    boolean evaluate(Map<String, Value> values) throws CheckEvaluationException;
}
