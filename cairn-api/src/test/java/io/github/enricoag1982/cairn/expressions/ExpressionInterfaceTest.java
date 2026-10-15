package io.github.enricoag1982.cairn.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The interface between the engine and an expression language (capability {@code checks}). */
class ExpressionInterfaceTest {

    @Test
    void aCompilationExceptionKnowsWhereOrNot() {
        CheckCompilationException unknown = new CheckCompilationException("Syntax error.");
        CheckCompilationException known = new CheckCompilationException("Syntax error.", 2, 7);

        assertThat(unknown.getMessage()).isEqualTo("Syntax error.");
        assertThat(unknown.line()).isEmpty();
        assertThat(unknown.column()).isEmpty();
        assertThat(known.line()).hasValue(2);
        assertThat(known.column()).hasValue(7);
    }

    @Test
    void positionsCountFromOne() {
        assertThatThrownBy(() -> new CheckCompilationException("x", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckCompilationException("x", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CheckCompilationException("x", 1, 1).column()).hasValue(1);
    }

    @Test
    void anEvaluationExceptionCarriesItsCause() {
        IllegalStateException cause = new IllegalStateException("boom");

        assertThat(new CheckEvaluationException("no such key").getMessage()).isEqualTo("no such key");
        assertThat(new CheckEvaluationException("failed", cause).getCause()).isSameAs(cause);
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
        };

        CompiledCheck check = language.compile("x", Map.of("x", ValueType.INT));

        assertThat(check.evaluate(Map.of("x", Value.of(1)))).isTrue();
        assertThat(check.evaluate(Map.of())).isFalse();
    }
}
