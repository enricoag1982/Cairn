package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An expression language for the tests of the engine's handling of checks, which reads no expression: the text of the
 * expression says what the language does. {@code true} and {@code false} are checks with that verdict, {@code evaluation
 * error} fails when it is evaluated, {@code crash when evaluated} throws a runtime exception when it is evaluated,
 * {@code rejected} does not compile, with the position 2 and 5, {@code rejected without position} does not compile and
 * does not say where, {@code crash when compiled} throws a runtime exception when it is compiled, and {@code nothing}
 * compiles to nothing. A derivation is compiled the same way, and its text says what it gives: {@code no value} gives none,
 * {@code wrong type} gives a boolean for an attribute that takes anything else, and a string for one that takes a
 * boolean, and anything else a value of the type of the attribute (a string {@code stub}, {@code true}, 1, 1L, 1.5, 2026-01-01 or the start of
 * that day). The language records what it was asked to compile and how often a check or a derivation was evaluated.
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

    /**
     * One request to compile a derivation.
     *
     * @param expression the expression
     * @param attributes the attributes the type has that are not derived
     * @param result     the value type of the derived attribute
     */
    public record CompiledDerived(String expression, Map<String, ValueType> attributes, ValueType result) {
    }

    private final List<Compiled> compiled = new CopyOnWriteArrayList<>();
    private final List<CompiledDerived> derivations = new CopyOnWriteArrayList<>();
    private final AtomicInteger evaluations = new AtomicInteger();

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public CompiledCheck compile(String expression, Map<String, ValueType> attributes)
            throws ExpressionCompilationException {
        compiled.add(new Compiled(expression, Map.copyOf(attributes)));
        return switch (expression) {
            case "rejected" -> throw new ExpressionCompilationException("The stub rejects this.", 2, 5);
            case "rejected without position" -> throw new ExpressionCompilationException("The stub rejects this too.");
            case "crash when compiled" -> throw new IllegalStateException("The stub crashed.");
            case "nothing" -> null;
            case "evaluation error" -> values -> {
                evaluations.incrementAndGet();
                throw new ExpressionEvaluationException("The stub cannot evaluate this.");
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

    @Override
    public CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes, ValueType result)
            throws ExpressionCompilationException {
        derivations.add(new CompiledDerived(expression, Map.copyOf(attributes), result));
        return switch (expression) {
            case "rejected" -> throw new ExpressionCompilationException("The stub rejects this.", 2, 5);
            case "rejected without position" -> throw new ExpressionCompilationException("The stub rejects this too.");
            case "crash when compiled" -> throw new IllegalStateException("The stub crashed.");
            case "nothing" -> null;
            case "evaluation error" -> values -> {
                evaluations.incrementAndGet();
                throw new ExpressionEvaluationException("The stub cannot evaluate this.");
            };
            case "crash when evaluated" -> values -> {
                evaluations.incrementAndGet();
                throw new IllegalStateException("The stub crashed while evaluating.");
            };
            case "no value" -> values -> {
                evaluations.incrementAndGet();
                return Optional.empty();
            };
            case "wrong type" -> values -> {
                evaluations.incrementAndGet();
                return Optional.of(result == ValueType.BOOLEAN ? Value.of("not a boolean") : Value.of(true));
            };
            default -> values -> {
                evaluations.incrementAndGet();
                return Optional.of(switch (result) {
                    case STRING -> Value.of("stub");
                    case BOOLEAN -> Value.of(true);
                    case INT -> Value.of(1);
                    case LONG -> Value.of(1L);
                    case DECIMAL -> Value.of(new java.math.BigDecimal("1.5"));
                    case DATE -> Value.of(java.time.LocalDate.parse("2026-01-01"));
                    case INSTANT -> Value.of(java.time.Instant.parse("2026-01-01T00:00:00Z"));
                });
            };
        };
    }

    /** What the language was asked to compile as a derivation, in order. */
    public List<CompiledDerived> derivations() {
        return derivations;
    }

    /** What the language was asked to compile, in order. */
    public List<Compiled> compiled() {
        return compiled;
    }

    /** How many times a check or a derivation was evaluated. */
    public int evaluations() {
        return evaluations.get();
    }
}
