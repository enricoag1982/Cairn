package io.github.enricoag1982.cairn.expressions;

import io.github.enricoag1982.cairn.schema.Value;
import java.util.Map;
import java.util.Optional;

/**
 * The expression of a derived attribute, compiled by an {@link ExpressionLanguage} for the attributes of one type and
 * the value type of the derived attribute (ADR-0031, capability {@code derived-attributes}).
 *
 * <p>A compiled derivation is immutable and thread-safe. Evaluating it reads nothing but the values it is given and has
 * no side effects.
 */
public interface CompiledDerivation {

    /**
     * Computes the value of the derived attribute for one version.
     *
     * @param values the values of the version's attributes that are not derived and have a value, by attribute name; an
     *               attribute without a value is absent
     * @return a value of the value type that the derivation was compiled for, or empty if the expression gives no value
     *         ({@code null} in the Common Expression Language)
     * @throws ExpressionEvaluationException if the evaluation gives no result, for example because the expression reads
     *                                       an attribute that has no value, or exceeds the cost limit of the language, or
     *                                       because its result does not fit the value type of the attribute, such as an
     *                                       integer outside the range of an {@code int}
     */
    Optional<Value> evaluate(Map<String, Value> values) throws ExpressionEvaluationException;
}
