package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.expressions.CheckCompilationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compiles the expressions of checks with the expression language of a registry, if it has one (design D2 of
 * {@code add-cel-expressions}). A registry compiles the checks of a definition when it validates, plans and registers it,
 * and again for each concrete type that inherits a check, so the compiler keeps what it compiled: an expression compiled
 * against the same attributes is compiled once.
 *
 * <p>A language that throws anything but a {@link CheckCompilationException} is reported as failing to compile the
 * expression: a fault in a language must not stop a registry.
 */
final class CheckCompiler {

    /** A compiler without an expression language. */
    static final CheckCompiler NONE = new CheckCompiler(Optional.empty());

    private record Key(String expression, Map<String, ValueType> attributes) {
    }

    private final Optional<ExpressionLanguage> language;
    private final ConcurrentMap<Key, CompiledCheck> compiled = new ConcurrentHashMap<>();

    CheckCompiler(Optional<ExpressionLanguage> language) {
        this.language = Objects.requireNonNull(language, "language");
    }

    /** Tells whether there is an expression language to compile with. */
    boolean available() {
        return language.isPresent();
    }

    /**
     * Compiles an expression against the attributes of a type.
     *
     * @param expression the expression
     * @param attributes the value types of the type's attributes, by name
     * @return the compiled check
     * @throws CheckCompilationException if the language cannot compile the expression
     * @throws IllegalStateException     if there is no expression language
     */
    CompiledCheck compile(String expression, Map<String, ValueType> attributes) throws CheckCompilationException {
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
        } catch (CheckCompilationException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw new CheckCompilationException("The expression language " + used.name() + " failed: " + e);
        }
        compiled.putIfAbsent(key, result);
        return result;
    }
}
