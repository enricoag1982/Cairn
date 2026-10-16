package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.HashMap;
import java.util.Map;

/**
 * How a compiled CEL program is run for the values of one version, for checks and derivations alike (design D3 of
 * {@code add-cel-expressions} and D2 of {@code add-derived-attributes}).
 *
 * <p>The program sees one variable, {@code self}: a map from the names of the attributes that have a value to those
 * values, converted by the value types of their attributes ({@link Values}). The run counts the nodes of the program that
 * are evaluated and stops when they exceed {@link CelLanguage#COST_LIMIT}.
 */
final class Evaluation {

    /** The name of the variable that holds the version's values. */
    static final String SELF = "self";

    /** What the listener throws to stop an evaluation that took too many steps. */
    private static final class CostLimitExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CostLimitExceeded() {
            super("cost limit", null, false, false);
        }
    }

    private Evaluation() {
    }

    /**
     * Runs a program.
     *
     * @param program    the compiled program
     * @param attributes the value types of the attributes the program was compiled for
     * @param values     the values of the version that has them, by attribute name
     * @param what       what the program is, for the messages: {@code check} or {@code derivation}
     * @return what the program evaluates to, as CEL for Java gives it
     * @throws ExpressionEvaluationException if a value does not fit its attribute, the program fails, or it takes more
     *                                       evaluation steps than the limit
     */
    static Object run(CelRuntime.Program program, Map<String, ValueType> attributes, Map<String, Value> values,
            String what) throws ExpressionEvaluationException {
        Map<String, Object> self = new HashMap<>();
        for (Map.Entry<String, ValueType> attribute : attributes.entrySet()) {
            Value value = values.get(attribute.getKey());
            if (value != null) {
                self.put(attribute.getKey(), Values.celValue(attribute.getKey(), attribute.getValue(), value));
            }
        }
        int[] steps = {0};
        try {
            return program.trace(Map.of(SELF, self), (expression, value) -> {
                if (++steps[0] > CelLanguage.COST_LIMIT) {
                    throw new CostLimitExceeded();
                }
            });
        } catch (CelEvaluationException e) {
            if (causedByCost(e)) {
                throw costLimit(what, e);
            }
            throw new ExpressionEvaluationException(e.getMessage(), e);
        } catch (CostLimitExceeded e) {
            throw costLimit(what, e);
        }
    }

    private static ExpressionEvaluationException costLimit(String what, Throwable cause) {
        return new ExpressionEvaluationException("The " + what + " took more than " + CelLanguage.COST_LIMIT
                + " evaluation steps, which is the limit.", cause);
    }

    private static boolean causedByCost(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CostLimitExceeded) {
                return true;
            }
        }
        return false;
    }
}
