package io.github.enricoag1982.cairn.cel;

import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The derivation of an attribute compiled to a CEL program for the attributes of one type (design D2 of
 * {@code add-derived-attributes}).
 *
 * <p>Evaluation runs the program as {@link Evaluation} describes, and maps its result to a value of the attribute's value
 * type as {@link Values#derived} does. The attributes that the expression reads are found when it is compiled, from the
 * checked tree, as {@link SelfReads} describes (design D1 of {@code add-attribute-access-control}). The program is
 * immutable, so one instance serves many threads.
 */
final class CelDerivation implements CompiledDerivation {

    private final CelRuntime.Program program;
    private final Map<String, ValueType> attributes;
    private final ValueType result;
    private final Set<String> attributesRead;

    CelDerivation(CelRuntime.Program program, Map<String, ValueType> attributes, ValueType result,
            Set<String> attributesRead) {
        this.program = program;
        this.attributes = attributes;
        this.result = result;
        this.attributesRead = attributesRead;
    }

    @Override
    public Set<String> attributesRead() {
        return attributesRead;
    }

    @Override
    public Optional<Value> evaluate(Map<String, Value> values) throws ExpressionEvaluationException {
        return Values.derived(result, Evaluation.run(program, attributes, values, "derivation"));
    }
}
