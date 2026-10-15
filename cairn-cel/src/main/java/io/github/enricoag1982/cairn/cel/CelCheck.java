package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CheckEvaluationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A check compiled to a CEL program for the attributes of one type (design D3 of {@code add-cel-expressions}).
 *
 * <p>Evaluation reads {@code self} as a map from the names of the attributes that have a value to those values, converted
 * by the value types of their attributes ({@link Values}). It counts the nodes of the program that are evaluated and stops
 * when they exceed {@link CelLanguage#COST_LIMIT}. The program is immutable, so one instance serves many threads.
 */
final class CelCheck implements CompiledCheck {

    /** The name of the variable that holds the version's values. */
    static final String SELF = "self";

    /** What the listener throws to stop an evaluation that took too many steps. */
    private static final class CostLimitExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CostLimitExceeded() {
            super("cost limit", null, false, false);
        }
    }

    private final CelRuntime.Program program;
    private final Map<String, ValueType> attributes;

    CelCheck(CelRuntime.Program program, Map<String, ValueType> attributes) {
        this.program = program;
        this.attributes = attributes;
    }

    @Override
    public boolean evaluate(Map<String, Value> values) throws CheckEvaluationException {
        Map<String, Object> self = new HashMap<>();
        for (Map.Entry<String, ValueType> attribute : attributes.entrySet()) {
            Value value = values.get(attribute.getKey());
            if (value != null) {
                self.put(attribute.getKey(), Values.celValue(attribute.getKey(), attribute.getValue(), value));
            }
        }
        int[] steps = {0};
        Object result;
        try {
            result = program.trace(Map.of(SELF, self), (expression, value) -> {
                if (++steps[0] > CelLanguage.COST_LIMIT) {
                    throw new CostLimitExceeded();
                }
            });
        } catch (CelEvaluationException e) {
            if (causedByCost(e)) {
                throw new CheckEvaluationException("The check took more than " + CelLanguage.COST_LIMIT
                        + " evaluation steps, which is the limit.", e);
            }
            throw new CheckEvaluationException(e.getMessage(), e);
        } catch (CostLimitExceeded e) {
            throw new CheckEvaluationException("The check took more than " + CelLanguage.COST_LIMIT
                    + " evaluation steps, which is the limit.", e);
        }
        if (result instanceof Boolean verdict) {
            return verdict;
        }
        throw new CheckEvaluationException("The result of the check is " + describe(result)
                + ", but it must be a boolean.");
    }

    private static boolean causedByCost(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CostLimitExceeded) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Object result) {
        return result == null ? "null" : "a " + result.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
