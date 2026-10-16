package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compiles the expressions of checks and of derived attributes with the expression language of a registry, if it has one
 * (design D2 of {@code add-cel-expressions} and D2 and D3 of {@code add-derived-attributes}). A registry compiles the
 * expressions of a definition when it validates, plans and registers it, and again for each concrete type that inherits
 * a check or an attribute, so the compiler keeps what it compiled: an expression compiled against the same attributes (and
 * for a derivation the same result type) is compiled once.
 *
 * <p>A language that throws anything but an {@link ExpressionCompilationException} is reported as failing to compile the
 * expression: a fault in a language must not stop a registry.
 */
final class ExpressionCompiler {

    /** A compiler without an expression language. */
    static final ExpressionCompiler NONE = new ExpressionCompiler(Optional.empty());

    private record Key(String expression, Map<String, ValueType> attributes) {
    }

    private record DerivationKey(String expression, Map<String, ValueType> attributes, ValueType result) {
    }

    private final Optional<ExpressionLanguage> language;
    private final ConcurrentMap<Key, CompiledCheck> compiled = new ConcurrentHashMap<>();
    private final ConcurrentMap<DerivationKey, CompiledDerivation> derivations = new ConcurrentHashMap<>();

    ExpressionCompiler(Optional<ExpressionLanguage> language) {
        this.language = Objects.requireNonNull(language, "language");
    }

    /** Tells whether there is an expression language to compile with. */
    boolean available() {
        return language.isPresent();
    }

    /**
     * Compiles the expression of a check against the attributes of a type.
     *
     * @param expression the expression
     * @param attributes the value types of the type's attributes, by name
     * @return the compiled check
     * @throws ExpressionCompilationException if the language cannot compile the expression
     * @throws IllegalStateException          if there is no expression language
     */
    CompiledCheck compile(String expression, Map<String, ValueType> attributes) throws ExpressionCompilationException {
        ExpressionLanguage used = language.orElseThrow(() -> new IllegalStateException("There is no expression language."));
        Key key = new Key(expression, Map.copyOf(attributes));
        CompiledCheck known = compiled.get(key);
        if (known != null) {
            return known;
        }
        CompiledCheck result;
        try {
            result = Objects.requireNonNull(used.compile(expression, key.attributes()),
                    "The expression language " + used.name() + " compiled the expression to nothing.");
        } catch (ExpressionCompilationException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw new ExpressionCompilationException("The expression language " + used.name() + " failed: " + e);
        }
        compiled.putIfAbsent(key, result);
        return result;
    }

    /**
     * Compiles the expression of a derived attribute against the attributes of a type that are not derived.
     *
     * @param expression the expression
     * @param attributes the value types of the type's attributes that are not derived, by name
     * @param result     the value type of the derived attribute
     * @return the compiled derivation
     * @throws ExpressionCompilationException if the language cannot compile the expression
     * @throws IllegalStateException          if there is no expression language
     */
    CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes, ValueType result)
            throws ExpressionCompilationException {
        ExpressionLanguage used = language.orElseThrow(() -> new IllegalStateException("There is no expression language."));
        DerivationKey key = new DerivationKey(expression, Map.copyOf(attributes), result);
        CompiledDerivation known = derivations.get(key);
        if (known != null) {
            return known;
        }
        CompiledDerivation compiledDerivation;
        try {
            compiledDerivation = Objects.requireNonNull(used.compileDerivation(expression, key.attributes(), result),
                    "The expression language " + used.name() + " compiled the expression to nothing.");
        } catch (ExpressionCompilationException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw new ExpressionCompilationException("The expression language " + used.name() + " failed: " + e);
        }
        derivations.putIfAbsent(key, compiledDerivation);
        return compiledDerivation;
    }
}
