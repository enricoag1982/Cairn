package io.github.enricoag1982.cairn.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.expressions.CheckCompilationException;
import io.github.enricoag1982.cairn.expressions.CheckEvaluationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The language of checks on CEL for Java: how each value type is seen by an expression, absent attributes, the limit
 * of an evaluation, and what a compilation reports (design D3 of {@code add-cel-expressions}).
 */
class CelLanguageTest {

    private static final Map<String, ValueType> ATTRIBUTES = Map.ofEntries(
            Map.entry("name", ValueType.STRING),
            Map.entry("active", ValueType.BOOLEAN),
            Map.entry("count", ValueType.INT),
            Map.entry("big", ValueType.LONG),
            Map.entry("balance", ValueType.DECIMAL),
            Map.entry("birthDate", ValueType.DATE),
            Map.entry("seenAt", ValueType.INSTANT));

    private final CelLanguage language = new CelLanguage();

    private CompiledCheck compile(String expression) throws CheckCompilationException {
        return language.compile(expression, ATTRIBUTES);
    }

    private boolean evaluate(String expression, Map<String, Value> values)
            throws CheckCompilationException, CheckEvaluationException {
        return compile(expression).evaluate(values);
    }

    private CheckCompilationException failure(String expression) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(CheckCompilationException.class,
                () -> language.compile(expression, ATTRIBUTES));
    }

    // ------------------------------------------------------------------ the language

    @Test
    @DisplayName("The language is named cel and found through the service loader")
    void theLanguageIsNamedCelAndFoundThroughTheServiceLoader() {
        assertThat(language.name()).isEqualTo("cel");
        assertThat(ServiceLoader.load(ExpressionLanguage.class).stream().map(ServiceLoader.Provider::type))
                .contains(CelLanguage.class);
    }

    // ------------------------------------------------------------------ the value types

    @Test
    @DisplayName("A string is a CEL string, also one with allowed values")
    void aStringIsACelString() throws Exception {
        String expression = "size(self.name) == 5 && self.name.startsWith('Al') && self.name == 'Alice'";

        assertThat(evaluate(expression, Map.of("name", Value.of("Alice")))).isTrue();
        assertThat(evaluate(expression, Map.of("name", Value.of("Alicia")))).isFalse();
    }

    @Test
    @DisplayName("A boolean is a CEL bool")
    void aBooleanIsACelBool() throws Exception {
        assertThat(evaluate("self.active", Map.of("active", Value.of(true)))).isTrue();
        assertThat(evaluate("self.active", Map.of("active", Value.of(false)))).isFalse();
        assertThat(evaluate("!self.active", Map.of("active", Value.of(false)))).isTrue();
    }

    @Test
    @DisplayName("An int and a long are CEL integers")
    void anIntAndALongAreCelIntegers() throws Exception {
        Map<String, Value> values = Map.of("count", Value.of(7), "big", Value.of(3_000_000_000L));

        assertThat(evaluate("self.count == 7 && self.count % 2 == 1", values)).isTrue();
        assertThat(evaluate("self.big == 3000000000 && self.big > self.count", values)).isTrue();
        assertThat(evaluate("self.big + self.big == 6000000000", values)).as("beyond the range of an int").isTrue();
        assertThat(evaluate("self.count > 7", values)).isFalse();
    }

    @Test
    @DisplayName("A decimal is a CEL double")
    void aDecimalIsACelDouble() throws Exception {
        Map<String, Value> values = Map.of("balance", Value.of(new BigDecimal("1500.50")));

        assertThat(evaluate("self.balance >= 0.0", values)).isTrue();
        assertThat(evaluate("self.balance == 1500.5", values)).isTrue();
        assertThat(evaluate("self.balance * 2.0 == 3001.0", values)).isTrue();
        assertThat(evaluate("self.balance < 1000.0", values)).isFalse();
    }

    @Test
    @DisplayName("CEL does not mix integers and doubles, and a number is converted by its attribute's type")
    void aNumberIsConvertedByItsAttributesType() throws Exception {
        assertThat(failure("self.balance >= 0").getMessage()).contains("no matching overload").contains("double, int");
        assertThat(failure("self.count > 2.5").getMessage()).contains("no matching overload").contains("int, double");
        assertThat(evaluate("self.balance >= 0.0", Map.of("balance", Value.of(-5)))).as("an int on a decimal").isFalse();
        assertThat(evaluate("self.balance == -5.0", Map.of("balance", Value.of(-5)))).isTrue();
        assertThat(evaluate("self.balance == 7.0", Map.of("balance", Value.of(7L)))).as("a long on a decimal").isTrue();
        assertThat(evaluate("self.big == 5", Map.of("big", Value.of(5)))).as("an int on a long").isTrue();
    }

    @Test
    @DisplayName("A date is a timestamp at 00:00 UTC")
    void aDateIsATimestampAtMidnightUtc() throws Exception {
        Map<String, Value> values = Map.of("birthDate", Value.of(LocalDate.of(1990, 5, 17)));

        assertThat(evaluate("self.birthDate == timestamp('1990-05-17T00:00:00Z')", values)).isTrue();
        assertThat(evaluate("self.birthDate < timestamp('1990-05-17T00:00:01Z')", values)).isTrue();
        assertThat(evaluate("self.birthDate.getFullYear() == 1990 && self.birthDate.getMonth() == 4", values)).isTrue();
        assertThat(evaluate("self.birthDate < timestamp('1990-05-17T00:00:00Z')", values)).isFalse();
    }

    @Test
    @DisplayName("An instant is a timestamp")
    void anInstantIsATimestamp() throws Exception {
        Map<String, Value> values = Map.of("seenAt", Value.of(Instant.parse("2026-03-01T10:15:30Z")));

        assertThat(evaluate("self.seenAt == timestamp('2026-03-01T10:15:30Z')", values)).isTrue();
        assertThat(evaluate("self.seenAt > timestamp('2026-03-01T10:15:29Z')", values)).isTrue();
        assertThat(evaluate("self.seenAt + duration('1h') == timestamp('2026-03-01T11:15:30Z')", values)).isTrue();
    }

    @Test
    @DisplayName("A date and an instant compare as timestamps")
    void aDateAndAnInstantCompareAsTimestamps() throws Exception {
        Map<String, Value> values = Map.of("birthDate", Value.of(LocalDate.of(2026, 3, 1)),
                "seenAt", Value.of(Instant.parse("2026-03-01T10:15:30Z")));

        assertThat(evaluate("self.birthDate < self.seenAt", values)).isTrue();
    }

    @Test
    @DisplayName("A value that does not fit its attribute is an evaluation error")
    void aValueThatDoesNotFitItsAttributeIsAnEvaluationError() throws Exception {
        CompiledCheck check = compile("self.count > 0");

        assertThatThrownBy(() -> check.evaluate(Map.of("count", Value.of("seven"))))
                .isInstanceOf(CheckEvaluationException.class).hasMessageContaining("count");
        assertThatThrownBy(() -> compile("self.big > 0").evaluate(Map.of("big", Value.of(new BigDecimal("1")))))
                .isInstanceOf(CheckEvaluationException.class);
    }

    // ------------------------------------------------------------------ absent attributes

    @Test
    @DisplayName("An attribute without a value is absent: has() is false and reading it is an evaluation error")
    void anAttributeWithoutAValueIsAbsent() throws Exception {
        Map<String, Value> none = Map.of();

        assertThat(evaluate("has(self.birthDate)", none)).isFalse();
        assertThat(evaluate("!has(self.birthDate) || self.birthDate < timestamp('2026-01-01T00:00:00Z')", none)).isTrue();
        assertThat(evaluate("has(self.count) && self.count > 0", none)).isFalse();
        assertThatThrownBy(() -> compile("self.birthDate < timestamp('2026-01-01T00:00:00Z')").evaluate(none))
                .isInstanceOf(CheckEvaluationException.class).hasMessageContaining("birthDate");
        assertThat(evaluate("has(self.count)", Map.of("count", Value.of(0)))).isTrue();
    }

    @Test
    @DisplayName("Values of attributes that the expression does not know are ignored")
    void valuesOfOtherAttributesAreIgnored() throws Exception {
        assertThat(evaluate("self.count == 1", Map.of("count", Value.of(1), "other", Value.of("x")))).isTrue();
    }

    // ------------------------------------------------------------------ the cost limit

    @Test
    @DisplayName("An evaluation that takes more than 10,000 steps is an evaluation error")
    void anEvaluationThatTakesMoreThanTenThousandStepsIsAnError() throws Exception {
        String ten = "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]";
        String cheap = ten + ".all(a, " + ten + ".all(b, a + b > 0))";
        String costly = ten + ".all(a, " + ten + ".all(b, " + ten + ".all(c, " + ten + ".all(d, a + b + c + d > 0))))";

        assertThat(CelLanguage.COST_LIMIT).isEqualTo(10_000);
        assertThat(evaluate(cheap, Map.of())).as("about 1,100 steps").isTrue();
        assertThatThrownBy(() -> evaluate(costly, Map.of()))
                .isInstanceOf(CheckEvaluationException.class)
                .hasMessageContaining("10000").hasMessageContaining("steps");
    }

    // ------------------------------------------------------------------ compilation

    @Test
    @DisplayName("A syntax error gives its line and column, counted from 1")
    void aSyntaxErrorGivesItsPosition() {
        CheckCompilationException error = failure("self.balance >=");

        assertThat(error.line()).hasValue(1);
        assertThat(error.column()).hasValue(16);
        assertThat(error.getMessage()).contains("<EOF>");
    }

    @Test
    @DisplayName("An unknown attribute is a compilation error at the select, and names the attribute")
    void anUnknownAttributeIsACompilationError() {
        CheckCompilationException error = failure("self.amount >= 0.0");

        assertThat(error.getMessage()).contains("amount");
        assertThat(error.line()).hasValue(1);
        assertThat(error.column()).hasValue(5);
    }

    @Test
    @DisplayName("An unknown attribute in has() or in a macro is found too")
    void anUnknownAttributeInHasOrInAMacroIsFound() {
        assertThat(failure("has(self.amount)").getMessage()).contains("amount");
        assertThat(failure("[1, 2].exists(i, self.amount > i)").getMessage()).contains("amount");
    }

    @Test
    @DisplayName("The position of an error on a later line counts from the start of that line")
    void thePositionOfAnErrorOnALaterLine() {
        CheckCompilationException error = failure("self.count > 0 &&\n  self.cnt > 1");

        assertThat(error.line()).hasValue(2);
        assertThat(error.column()).hasValue(7);
    }

    @Test
    @DisplayName("Columns count code points")
    void columnsCountCodePoints() {
        CheckCompilationException error = failure("'日本😀' + self.nme");

        assertThat(error.line()).hasValue(1);
        assertThat(error.column()).as("the dot after self is the 13th code point and the 14th char").hasValue(13);
    }

    @Test
    @DisplayName("A type error is found when the check is compiled")
    void aTypeErrorIsFoundWhenTheCheckIsCompiled() {
        assertThat(failure("self.name > 5").getMessage()).contains("no matching overload");
        assertThat(failure("self.count == 'a'").getMessage()).contains("no matching overload");
        assertThat(failure("size(self.count) > 0").getMessage()).contains("size");
    }

    @Test
    @DisplayName("A name other than self is undeclared")
    void aNameOtherThanSelfIsUndeclared() {
        assertThat(failure("other.count > 0").getMessage()).contains("other");
        assertThat(failure("count > 0").getMessage()).contains("count");
    }

    @Test
    @DisplayName("A result that is not a boolean is a compilation error")
    void aResultThatIsNotABooleanIsACompilationError() {
        CheckCompilationException number = failure("self.balance + 1.0");
        CheckCompilationException text = failure("self.name");
        CheckCompilationException list = failure("[self.count]");

        assertThat(number.getMessage()).contains("double").contains("boolean");
        assertThat(text.getMessage()).contains("string").contains("boolean");
        assertThat(list.getMessage()).contains("list").contains("boolean");
        assertThat(text.line()).hasValue(1);
        assertThat(text.column()).hasValue(5);
    }

    @Test
    @DisplayName("A result of unknown type is accepted, and checked when it is evaluated")
    void aResultOfUnknownTypeIsAccepted() throws Exception {
        assertThat(evaluate("dyn(true)", Map.of())).isTrue();
        assertThat(evaluate("dyn(self.count) == 3", Map.of("count", Value.of(3)))).isTrue();
        assertThatThrownBy(() -> evaluate("dyn(1)", Map.of()))
                .isInstanceOf(CheckEvaluationException.class).hasMessageContaining("boolean");
    }

    @Test
    @DisplayName("A literal true or false is a check")
    void aLiteralIsACheck() throws Exception {
        assertThat(evaluate("true", Map.of())).isTrue();
        assertThat(evaluate("false", Map.of())).isFalse();
    }

    @Test
    @DisplayName("An expression with more than 4,000 characters does not compile")
    void anExpressionWithMoreThanFourThousandCharactersDoesNotCompile() {
        String longest = "true" + " && true".repeat(499);

        assertThat(longest.length()).isEqualTo(3_996);
        assertThatThrownBy(() -> compile(longest + " && true && true")).isInstanceOf(CheckCompilationException.class);
        assertThat(compileQuietly(longest)).isNotNull();
    }

    @Test
    @DisplayName("An expression nested more deeply than CEL allows does not compile")
    void aTooDeeplyNestedExpressionDoesNotCompile() {
        String nested = "(".repeat(300) + "true" + ")".repeat(300);

        assertThatThrownBy(() -> compile(nested)).isInstanceOf(CheckCompilationException.class);
    }

    @Test
    @DisplayName("An attribute whose name is not an attribute of the type is unknown, also if the type has no attributes")
    void aTypeWithoutAttributesHasNothingToRead() throws Exception {
        assertThatThrownBy(() -> language.compile("self.count > 0", Map.of()))
                .isInstanceOf(CheckCompilationException.class).hasMessageContaining("count");
        assertThat(language.compile("true", Map.of()).evaluate(Map.of())).isTrue();
    }

    // ------------------------------------------------------------------ threads

    @Test
    @DisplayName("A compiled check is evaluated by several threads at once")
    void aCompiledCheckIsEvaluatedByManyThreads() throws Exception {
        CompiledCheck check = compile("self.count == self.big - 1 && self.name.size() > 0");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                results.add(pool.submit(() -> check.evaluate(Map.of("count", Value.of(n), "big", Value.of(n + 1L),
                        "name", Value.of("x")))));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
    }

    private CompiledCheck compileQuietly(String expression) {
        try {
            return compile(expression);
        } catch (CheckCompilationException e) {
            throw new AssertionError(e);
        }
    }
}
