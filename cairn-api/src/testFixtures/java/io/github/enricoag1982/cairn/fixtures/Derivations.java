package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiled derivations for tests of expression languages and of the engine's handling of them, made from a function and
 * the names of the attributes it says it reads, so that a test does not need a language.
 */
public final class Derivations {

    /** What a test derivation computes from the values of a version. */
    @FunctionalInterface
    public interface Body {

        /**
         * Computes the value of the derived attribute.
         *
         * @param values the values of the version's attributes that are not derived, by name
         * @return the value, or empty for none
         * @throws ExpressionEvaluationException if the evaluation gives no result
         */
        Optional<Value> evaluate(Map<String, Value> values) throws ExpressionEvaluationException;
    }

    private Derivations() {
    }

    /**
     * A derivation that reads no attribute by name.
     *
     * @param body what it computes
     * @return the derivation, whose {@link CompiledDerivation#attributesRead()} is empty
     */
    public static CompiledDerivation of(Body body) {
        return reading(Set.of(), body);
    }

    /**
     * A derivation that says which attributes it reads.
     *
     * @param attributesRead the names of the attributes it reads
     * @param body           what it computes
     * @return the derivation
     */
    public static CompiledDerivation reading(Set<String> attributesRead, Body body) {
        Set<String> read = Set.copyOf(attributesRead);
        return new CompiledDerivation() {
            @Override
            public Set<String> attributesRead() {
                return read;
            }

            @Override
            public Optional<Value> evaluate(Map<String, Value> values) throws ExpressionEvaluationException {
                return body.evaluate(values);
            }
        };
    }
}
