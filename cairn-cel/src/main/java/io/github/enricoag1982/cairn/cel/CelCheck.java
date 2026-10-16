package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A check compiled to a CEL program for the attributes of one type (design D3 of {@code add-cel-expressions}).
 *
 * <p>Evaluation runs the program as {@link Evaluation} describes. The attributes that the expression reads are found
 * when it is compiled, from the checked tree, as {@link SelfReads} describes (ADR-0036). The program is immutable, so
 * one instance serves many threads.
 */
final class CelCheck implements CompiledCheck {

    private final CelRuntime.Program program;
    private final Map<String, ValueType> attributes;
    private final Set<String> attributesRead;

    CelCheck(CelRuntime.Program program, Map<String, ValueType> attributes, Set<String> attributesRead) {
        this.program = program;
        this.attributes = attributes;
        this.attributesRead = attributesRead;
    }

    @Override
    public Optional<Set<String>> attributesRead() {
        return Optional.of(attributesRead);
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
