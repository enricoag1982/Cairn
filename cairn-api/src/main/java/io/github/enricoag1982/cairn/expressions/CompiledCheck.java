package io.github.enricoag1982.cairn.expressions;

import io.github.enricoag1982.cairn.schema.Value;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The expression of a check, compiled by an {@link ExpressionLanguage} for the attributes of one type (ADR-0030,
 * capability {@code checks}).
 *
 * <p>A compiled check is immutable and thread-safe. Evaluating it reads nothing but the values it is given and has no
 * side effects.
 */
public interface CompiledCheck {

    /**
     * The attributes that the expression reads from {@code self}, by name, as far as the language can tell without
     * evaluating it: every attribute that it selects or tests for a value. The engine compares their classifications
     * with the clearance of the one who writes or registers (capability {@code attribute-access}, ADR-0036), so that a
     * check cannot be used to probe an attribute that the clearance does not cover: a problem of a check that reads
     * one carries no detail of the evaluation, and an agent cannot add such a check. A language must not leave out an
     * attribute that an evaluation can read.
     *
     * <p>The default says that the language cannot tell, which the engine takes to mean that the check reads every
     * attribute of its type.
     *
     * @return the names of the attributes that the expression reads, as the attributes of the type are named, never
     *         null; empty if the language cannot tell, and then the check is taken to read every attribute of its type
     */
    default Optional<Set<String>> attributesRead() {
        return Optional.empty();
    }

    /**
     * Evaluates the check for one version.
     *
     * @param values the values of the version that has them, by attribute name; an attribute without a value is
     *               absent
     * @return {@code true} if the version satisfies the check
     * @throws ExpressionEvaluationException if the evaluation gives no result, for example because the expression reads an
     *                                  attribute that has no value, or exceeds the cost limit of the language
     */
    boolean evaluate(Map<String, Value> values) throws ExpressionEvaluationException;
}
