package io.github.enricoag1982.cairn.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The interface between the engine and an expression language (capabilities {@code checks} and {@code derived-attributes}). */
class ExpressionInterfaceTest {

    @Test
    void aCompilationExceptionKnowsWhereOrNot() {
        ExpressionCompilationException unknown = new ExpressionCompilationException("Syntax error.");
        ExpressionCompilationException known = new ExpressionCompilationException("Syntax error.", 2, 7);

        assertThat(unknown.getMessage()).isEqualTo("Syntax error.");
        assertThat(unknown.line()).isEmpty();
        assertThat(unknown.column()).isEmpty();
        assertThat(known.line()).hasValue(2);
        assertThat(known.column()).hasValue(7);
    }

    @Test
    void positionsCountFromOne() {
        assertThatThrownBy(() -> new ExpressionCompilationException("x", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpressionCompilationException("x", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ExpressionCompilationException("x", 1, 1).column()).hasValue(1);
    }

    @Test
    void anEvaluationExceptionCarriesItsCause() {
        IllegalStateException cause = new IllegalStateException("boom");

        assertThat(new ExpressionEvaluationException("no such key").getMessage()).isEqualTo("no such key");
        assertThat(new ExpressionEvaluationException("failed", cause).getCause()).isSameAs(cause);
    }

    @Test
    void aLanguageCompilesAndEvaluates() throws Exception {
        ExpressionLanguage language = new ExpressionLanguage() {
            @Override
            public String name() {
                return "always";
            }

            @Override
            public CompiledCheck compile(String expression, Map<String, ValueType> attributes) {
                return values -> values.containsKey("x");
            }

            @Override
            public CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes,
                    ValueType result) {
                return values -> Optional.ofNullable(values.get("x"));
            }
        };

        CompiledCheck check = language.compile("x", Map.of("x", ValueType.INT));

        assertThat(check.evaluate(Map.of("x", Value.of(1)))).isTrue();
        assertThat(check.evaluate(Map.of())).isFalse();
    }

    @Test
    void aLanguageCompilesDerivationsThatGiveAValueOrNone() throws Exception {
        ExpressionLanguage language = new ExpressionLanguage() {
            @Override
            public String name() {
                return "copy";
            }

            @Override
            public CompiledCheck compile(String expression, Map<String, ValueType> attributes) {
                return values -> true;
            }

            @Override
            public CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes,
                    ValueType result) {
                return values -> Optional.ofNullable(values.get("x"));
            }
        };

        CompiledDerivation derivation = language.compileDerivation("x", Map.of("x", ValueType.INT), ValueType.INT);

        assertThat(derivation.evaluate(Map.of("x", Value.of(1)))).contains(Value.of(1));
        assertThat(derivation.evaluate(Map.of())).isEmpty();
    }

    @Test
    void anEvaluationExceptionIsWhatADerivationThrows() {
        CompiledDerivation failing = values -> {
            throw new ExpressionEvaluationException("The result does not fit.");
        };

        assertThatThrownBy(() -> failing.evaluate(Map.of())).isInstanceOf(ExpressionEvaluationException.class)
                .hasMessage("The result does not fit.");
    }
}
