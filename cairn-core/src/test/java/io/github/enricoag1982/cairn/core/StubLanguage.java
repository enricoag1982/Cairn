package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.expressions.CheckCompilationException;
import io.github.enricoag1982.cairn.expressions.CheckEvaluationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An expression language for the tests of the engine's handling of checks, which reads no expression: the text of the
 * expression says what the language does. {@code true} and {@code false} are checks with that verdict, {@code evaluation
 * error} fails when it is evaluated, {@code crash when evaluated} throws a runtime exception when it is evaluated,
 * {@code rejected} does not compile, with the position 2 and 5, {@code rejected without position} does not compile and
 * does not say where, {@code crash when compiled} throws a runtime exception when it is compiled, and {@code nothing}
 * compiles to nothing. The language records what it was asked to compile and how often a check was evaluated.
 */
public final class StubLanguage implements ExpressionLanguage {

    /**
     * One request to compile.
     *
     * @param expression the expression
     * @param attributes the attributes the type has
     */
    public record Compiled(String expression, Map<String, ValueType> attributes) {
    }

    private final List<Compiled> compiled = new CopyOnWriteArrayList<>();
    private final AtomicInteger evaluations = new AtomicInteger();

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public CompiledCheck compile(String expression, Map<String, ValueType> attributes)
            throws CheckCompilationException {
        compiled.add(new Compiled(expression, Map.copyOf(attributes)));
        return switch (expression) {
            case "rejected" -> throw new CheckCompilationException("The stub rejects this.", 2, 5);
            case "rejected without position" -> throw new CheckCompilationException("The stub rejects this too.");
            case "crash when compiled" -> throw new IllegalStateException("The stub crashed.");
            case "nothing" -> null;
            case "evaluation error" -> values -> {
                evaluations.incrementAndGet();
                throw new CheckEvaluationException("The stub cannot evaluate this.");
            };
            case "crash when evaluated" -> values -> {
                evaluations.incrementAndGet();
                throw new IllegalStateException("The stub crashed while evaluating.");
            };
            default -> new CompiledCheck() {
                @Override
                public boolean evaluate(Map<String, Value> values) {
                    evaluations.incrementAndGet();
                    return !expression.equals("false");
                }
            };
        };
    }

    /** What the language was asked to compile, in order. */
    public List<Compiled> compiled() {
        return compiled;
    }

    /** How many times a check was evaluated. */
    public int evaluations() {
        return evaluations.get();
    }
}
