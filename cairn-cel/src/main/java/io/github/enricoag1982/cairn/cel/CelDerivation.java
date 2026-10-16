package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Optional;

/**
 * The derivation of an attribute compiled to a CEL program for the attributes of one type (design D2 of
 * {@code add-derived-attributes}).
 *
 * <p>Evaluation runs the program as {@link Evaluation} describes, and maps its result to a value of the attribute's value
 * type as {@link Values#derived} does. The program is immutable, so one instance serves many threads.
 */
final class CelDerivation implements CompiledDerivation {

    private final CelRuntime.Program program;
    private final Map<String, ValueType> attributes;
    private final ValueType result;

    CelDerivation(CelRuntime.Program program, Map<String, ValueType> attributes, ValueType result) {
        this.program = program;
        this.attributes = attributes;
        this.result = result;
    }

    @Override
    public Optional<Value> evaluate(Map<String, Value> values) throws ExpressionEvaluationException {
        return Values.derived(result, Evaluation.run(program, attributes, values, "derivation"));
    }
}
