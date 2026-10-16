package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Locale;
import java.util.Map;

/**
 * A check compiled to a CEL program for the attributes of one type (design D3 of {@code add-cel-expressions}).
 *
 * <p>Evaluation runs the program as {@link Evaluation} describes. The program is immutable, so one instance serves many
 * threads.
 */
final class CelCheck implements CompiledCheck {

    private final CelRuntime.Program program;
    private final Map<String, ValueType> attributes;

    CelCheck(CelRuntime.Program program, Map<String, ValueType> attributes) {
        this.program = program;
        this.attributes = attributes;
    }

    @Override
    public boolean evaluate(Map<String, Value> values) throws ExpressionEvaluationException {
        Object result = Evaluation.run(program, attributes, values, "check");
        if (result instanceof Boolean verdict) {
            return verdict;
        }
        throw new ExpressionEvaluationException("The result of the check is " + describe(result)
                + ", but it must be a boolean.");
    }

    private static String describe(Object result) {
        return result == null ? "null" : "a " + result.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
