package io.github.enricoag1982.cairn.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The derivations of the language on CEL for Java: how a result becomes a value of each value type, the expected type
 * that the checker is given, {@code null}, errors, and the limit of an evaluation (design D2 of
 * {@code add-derived-attributes}).
 */
class CelDerivationTest {

    private static final Map<String, ValueType> ATTRIBUTES = Map.ofEntries(
            Map.entry("name", ValueType.STRING),
            Map.entry("active", ValueType.BOOLEAN),
            Map.entry("count", ValueType.INT),
            Map.entry("big", ValueType.LONG),
            Map.entry("balance", ValueType.DECIMAL),
            Map.entry("birthDate", ValueType.DATE),
            Map.entry("seenAt", ValueType.INSTANT));

    private final CelLanguage language = new CelLanguage();

    private CompiledDerivation compile(String expression, ValueType result) throws ExpressionCompilationException {
        return language.compileDerivation(expression, ATTRIBUTES, result);
    }

    private Optional<Value> derive(String expression, ValueType result, Map<String, Value> values) throws Exception {
        return compile(expression, result).evaluate(values);
    }

    private ExpressionCompilationException failure(String expression, ValueType result) {
        return catchThrowableOfType(ExpressionCompilationException.class,
                () -> language.compileDerivation(expression, ATTRIBUTES, result));
    }

    private ExpressionEvaluationException evaluationFailure(String expression, ValueType result,
            Map<String, Value> values) throws ExpressionCompilationException {
        CompiledDerivation derivation = compile(expression, result);
        return catchThrowableOfType(ExpressionEvaluationException.class, () -> derivation.evaluate(values));
    }

    // ------------------------------------------------------------------ the result types

    @Test
    @DisplayName("A string result is a string, also for an attribute with allowed values")
    void aStringResultIsAString() throws Exception {
        Map<String, Value> values = Map.of("name", Value.of("Dev"), "count", Value.of(3));

        assertThat(derive("self.name + ' Patel'", ValueType.STRING, values)).contains(Value.of("Dev Patel"));
        assertThat(derive("string(self.count) + ' items'", ValueType.STRING, values)).contains(Value.of("3 items"));
        assertThat(derive("''", ValueType.STRING, values)).contains(Value.of(""));
    }

    @Test
    @DisplayName("A bool result is a boolean")
    void aBoolResultIsABoolean() throws Exception {
        assertThat(derive("self.count > 2", ValueType.BOOLEAN, Map.of("count", Value.of(3)))).contains(Value.of(true));
        assertThat(derive("self.count > 2", ValueType.BOOLEAN, Map.of("count", Value.of(1)))).contains(Value.of(false));
    }

    @Test
    @DisplayName("An integer result is an int within the range of an int, and a long")
    void anIntegerResultIsAnIntWithinTheRangeOfAnIntAndALong() throws Exception {
        Map<String, Value> values = Map.of("count", Value.of(7), "big", Value.of(3_000_000_000L));

        assertThat(derive("self.count * 2", ValueType.INT, values)).contains(Value.of(14));
        assertThat(derive("2147483647", ValueType.INT, values)).as("the largest int").contains(Value.of(Integer.MAX_VALUE));
        assertThat(derive("-2147483648", ValueType.INT, values)).as("the smallest int").contains(Value.of(Integer.MIN_VALUE));
        assertThat(derive("self.big", ValueType.LONG, values)).contains(Value.of(3_000_000_000L));
        assertThat(derive("self.count", ValueType.LONG, values)).as("an int is a long").contains(Value.of(7L));
        assertThat(derive("9223372036854775807", ValueType.LONG, values)).contains(Value.of(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("An integer result outside the range of an int does not fit an int")
    void anIntegerResultOutsideTheRangeOfAnIntDoesNotFit() throws Exception {
        Map<String, Value> values = Map.of("big", Value.of(3_000_000_000L));

        assertThat(evaluationFailure("self.big", ValueType.INT, values)).hasMessageContaining("3000000000")
                .hasMessageContaining("range of an int");
        assertThat(evaluationFailure("2147483648", ValueType.INT, values)).hasMessageContaining("2147483648");
        assertThat(evaluationFailure("-2147483649", ValueType.INT, values)).hasMessageContaining("-2147483649");
    }

    @Test
    @DisplayName("A double result is a decimal, taken at its shortest decimal form")
    void aDoubleResultIsADecimalAtItsShortestDecimalForm() throws Exception {
        Map<String, Value> values = Map.of("balance", Value.of(new BigDecimal("1500.50")));

        assertThat(derive("self.balance * 2.0", ValueType.DECIMAL, values)).contains(Value.of(new BigDecimal("3001.0")));
        assertThat(derive("0.1 + 0.2", ValueType.DECIMAL, values)).as("a double is only as exact as a double")
                .contains(Value.of(new BigDecimal("0.30000000000000004")));
        assertThat(derive("self.balance", ValueType.DECIMAL, values)).contains(Value.of(new BigDecimal("1500.5")));
        assertThat(derive("1e21", ValueType.DECIMAL, values)).contains(Value.of(BigDecimal.valueOf(1e21)));
    }

    @Test
    @DisplayName("NaN and infinities do not fit a decimal")
    void notANumberAndInfinitiesDoNotFitADecimal() throws Exception {
        Map<String, Value> values = Map.of("balance", Value.of(BigDecimal.TEN));

        assertThat(evaluationFailure("self.balance / 0.0", ValueType.DECIMAL, values)).hasMessageContaining("Infinity")
                .hasMessageContaining("decimal");
        assertThat(evaluationFailure("-self.balance / 0.0", ValueType.DECIMAL, values))
                .hasMessageContaining("-Infinity");
        assertThat(evaluationFailure("self.balance / 0.0 - self.balance / 0.0", ValueType.DECIMAL, values))
                .hasMessageContaining("NaN");
    }

    @Test
    @DisplayName("A timestamp result is an instant")
    void aTimestampResultIsAnInstant() throws Exception {
        Map<String, Value> values = Map.of("seenAt", Value.of(Instant.parse("2026-03-01T10:15:30Z")));

        assertThat(derive("self.seenAt + duration('1h')", ValueType.INSTANT, values))
                .contains(Value.of(Instant.parse("2026-03-01T11:15:30Z")));
        assertThat(derive("timestamp('2026-03-01T10:15:30.123456789Z')", ValueType.INSTANT, values))
                .contains(Value.of(Instant.parse("2026-03-01T10:15:30.123456789Z")));
    }

    @Test
    @DisplayName("A timestamp result at the start of a day in UTC is a date")
    void aTimestampResultAtTheStartOfADayInUtcIsADate() throws Exception {
        Map<String, Value> values = Map.of("birthDate", Value.of(LocalDate.of(1990, 5, 17)),
                "seenAt", Value.of(Instant.parse("2026-03-01T00:00:00Z")));

        assertThat(derive("self.birthDate", ValueType.DATE, values)).contains(Value.of(LocalDate.of(1990, 5, 17)));
        assertThat(derive("self.seenAt", ValueType.DATE, values)).contains(Value.of(LocalDate.of(2026, 3, 1)));
        assertThat(derive("self.birthDate + duration('24h')", ValueType.DATE, values))
                .contains(Value.of(LocalDate.of(1990, 5, 18)));
        assertThat(derive("timestamp('2026-12-31T00:00:00Z')", ValueType.DATE, values))
                .contains(Value.of(LocalDate.of(2026, 12, 31)));
    }

    @Test
    @DisplayName("A timestamp result that is not at the start of a day in UTC does not fit a date")
    void aTimestampResultThatIsNotAtTheStartOfADayDoesNotFitADate() throws Exception {
        Map<String, Value> values = Map.of("seenAt", Value.of(Instant.parse("2026-03-01T10:00:00Z")),
                "birthDate", Value.of(LocalDate.of(1990, 5, 17)));

        assertThat(evaluationFailure("self.seenAt", ValueType.DATE, values)).hasMessageContaining("2026-03-01T10:00:00Z")
                .hasMessageContaining("start of a day in UTC");
        assertThat(evaluationFailure("self.birthDate + duration('1s')", ValueType.DATE, values))
                .hasMessageContaining("1990-05-17T00:00:01Z");
        assertThat(evaluationFailure("timestamp('2026-03-01T00:00:00.000000001Z')", ValueType.DATE, values))
                .hasMessageContaining("start of a day");
        assertThat(evaluationFailure("timestamp('2026-03-01T00:00:00+01:00')", ValueType.DATE, values))
                .as("midnight in UTC+1 is not the start of a day in UTC").hasMessageContaining("2026-02-28T23:00:00Z");
        assertThat(derive("timestamp('2026-03-01T01:00:00+01:00')", ValueType.DATE, values))
                .as("but 01:00 in UTC+1 is").contains(Value.of(LocalDate.of(2026, 3, 1)));
    }

    // ------------------------------------------------------------------ null

    @Test
    @DisplayName("null is no value")
    void nullIsNoValue() throws Exception {
        Map<String, Value> bob = Map.of("birthDate", Value.of(LocalDate.of(1990, 5, 1)));
        Map<String, Value> alice = Map.of();

        assertThat(derive("has(self.birthDate) ? self.birthDate : null", ValueType.DATE, bob))
                .contains(Value.of(LocalDate.of(1990, 5, 1)));
        assertThat(derive("has(self.birthDate) ? self.birthDate : null", ValueType.DATE, alice)).isEmpty();
        assertThat(derive("has(self.birthDate) ? self.birthDate + duration('1s') : null", ValueType.INSTANT, alice))
                .isEmpty();
        assertThat(derive("dyn(null)", ValueType.STRING, alice)).isEmpty();
    }

    @Test
    @DisplayName("null as an int, string, bool or double through dyn is no value")
    void nullThroughDynIsNoValue() throws Exception {
        String birthYear = "has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null";

        assertThat(derive(birthYear, ValueType.INT, Map.of("birthDate", Value.of(LocalDate.of(1990, 5, 1)))))
                .contains(Value.of(1990));
        assertThat(derive(birthYear, ValueType.INT, Map.of())).isEmpty();
        assertThat(derive("has(self.name) ? dyn(self.name) : null", ValueType.STRING, Map.of())).isEmpty();
        assertThat(derive("has(self.active) ? dyn(self.active) : null", ValueType.BOOLEAN, Map.of())).isEmpty();
        assertThat(derive("has(self.balance) ? dyn(self.balance) : null", ValueType.DECIMAL, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("A conditional with null for an int, a string, a bool or a double needs dyn to type check")
    void aConditionalWithNullNeedsDynToTypeCheck() {
        for (String conditional : List.of(
                "has(self.birthDate) ? self.birthDate.getFullYear() : null",
                "has(self.name) ? self.name : null",
                "has(self.active) ? self.active : null",
                "has(self.balance) ? self.balance : null")) {
            ValueType result = conditional.contains("getFullYear") ? ValueType.INT : conditional.contains("name")
                    ? ValueType.STRING : conditional.contains("active") ? ValueType.BOOLEAN : ValueType.DECIMAL;

            ExpressionCompilationException error = failure(conditional, result);

            assertThat(error.getMessage()).as(conditional).contains("_?_:_").contains("null");
        }
    }

    // ------------------------------------------------------------------ what the checker is given

    @Test
    @DisplayName("The expected type is given to the checker: a result of another type does not compile")
    void theExpectedTypeIsGivenToTheChecker() {
        assertThat(failure("self.balance * 100.0", ValueType.INT).getMessage()).contains("int").contains("double");
        assertThat(failure("self.balance * 100.0", ValueType.LONG).getMessage()).contains("int").contains("double");
        assertThat(failure("self.name", ValueType.INT).getMessage()).contains("int").contains("string");
        assertThat(failure("self.count", ValueType.STRING).getMessage()).contains("string").contains("int");
        assertThat(failure("self.count", ValueType.DECIMAL).getMessage()).as("CEL does not convert an int to a double")
                .contains("double").contains("int");
        assertThat(failure("self.name", ValueType.DATE).getMessage()).contains("Timestamp").contains("string");
        assertThat(failure("[self.count]", ValueType.INT).getMessage()).contains("list");
        assertThat(failure("self.count > 1", ValueType.INT).getMessage()).contains("bool");
    }

    @Test
    @DisplayName("A result of unknown type compiles, and is checked when it is evaluated")
    void aResultOfUnknownTypeCompilesAndIsCheckedWhenEvaluated() throws Exception {
        assertThat(derive("dyn(1)", ValueType.INT, Map.of())).contains(Value.of(1));
        assertThat(evaluationFailure("dyn(1)", ValueType.STRING, Map.of()).getMessage())
                .contains("an integer").contains("string");
        assertThat(evaluationFailure("dyn('x')", ValueType.BOOLEAN, Map.of()).getMessage())
                .contains("a string").contains("boolean");
        assertThat(evaluationFailure("dyn(1.5)", ValueType.INT, Map.of()).getMessage()).contains("a double");
        assertThat(evaluationFailure("dyn(1)", ValueType.INSTANT, Map.of()).getMessage()).contains("timestamp");
        assertThat(evaluationFailure("dyn(1)", ValueType.DATE, Map.of()).getMessage())
                .contains("timestamp at the start of a day");
        assertThat(evaluationFailure("dyn(duration('1s'))", ValueType.STRING, Map.of()).getMessage())
                .contains("a duration");
        assertThat(evaluationFailure("dyn([1])", ValueType.STRING, Map.of()).getMessage()).contains("a list");
    }

    @Test
    @DisplayName("self holds only the attributes it is given: a derived attribute is not among them")
    void selfHoldsOnlyTheAttributesItIsGiven() {
        Map<String, ValueType> notDerived = Map.of("number", ValueType.STRING);

        ExpressionCompilationException error = catchThrowableOfType(ExpressionCompilationException.class,
                () -> language.compileDerivation("self.label + '!'", notDerived, ValueType.STRING));

        assertThat(error.getMessage()).contains("label");
        assertThat(error.line()).hasValue(1);
        assertThat(error.column()).hasValue(5);
    }

    @Test
    @DisplayName("A syntax error and an unknown attribute give their positions, as for checks")
    void aSyntaxErrorAndAnUnknownAttributeGiveTheirPositions() {
        ExpressionCompilationException syntax = failure("self.name +", ValueType.STRING);
        ExpressionCompilationException unknown = failure("self.name + self.nme", ValueType.STRING);
        ExpressionCompilationException later = failure("self.name +\n  self.nme", ValueType.STRING);

        assertThat(syntax.line()).hasValue(1);
        assertThat(syntax.column()).hasValue(12);
        assertThat(unknown.getMessage()).contains("nme");
        assertThat(unknown.column()).hasValue(17);
        assertThat(later.line()).hasValue(2);
        assertThat(later.column()).hasValue(7);
    }

    @Test
    @DisplayName("The same expression compiles for one result type and not for another")
    void theSameExpressionCompilesForOneResultTypeAndNotForAnother() throws Exception {
        assertThat(derive("self.count", ValueType.INT, Map.of("count", Value.of(5)))).contains(Value.of(5));
        assertThat(derive("self.count", ValueType.LONG, Map.of("count", Value.of(5)))).contains(Value.of(5L));
        assertThat(failure("self.count", ValueType.STRING)).isNotNull();
        assertThat(derive("self.count", ValueType.INT, Map.of("count", Value.of(6)))).contains(Value.of(6));
    }

    @Test
    @DisplayName("An expression with more than 4,000 characters does not compile")
    void anExpressionWithMoreThanFourThousandCharactersDoesNotCompile() throws Exception {
        String longest = "'" + "a".repeat(3_998) + "'";
        assertThat(longest.length()).isEqualTo(4_000);

        assertThat(compile(longest, ValueType.STRING).evaluate(Map.of())).contains(Value.of("a".repeat(3_998)));
        assertThatThrownBy(() -> compile("'" + "a".repeat(3_999) + "'", ValueType.STRING))
                .isInstanceOf(ExpressionCompilationException.class);
    }

    // ------------------------------------------------------------------ evaluation

    @Test
    @DisplayName("An attribute without a value is absent: has() is false and reading it is an evaluation error")
    void anAttributeWithoutAValueIsAbsent() throws Exception {
        Map<String, Value> none = Map.of();

        assertThat(derive("has(self.name) ? self.name : 'unknown'", ValueType.STRING, none))
                .contains(Value.of("unknown"));
        assertThat(evaluationFailure("self.name", ValueType.STRING, none)).hasMessageContaining("name");
        assertThat(evaluationFailure("self.birthDate.getFullYear()", ValueType.INT, none))
                .hasMessageContaining("birthDate");
    }

    @Test
    @DisplayName("A value that does not fit its attribute is an evaluation error")
    void aValueThatDoesNotFitItsAttributeIsAnEvaluationError() throws Exception {
        ExpressionEvaluationException error = evaluationFailure("self.count + 1", ValueType.INT,
                Map.of("count", Value.of("seven")));

        assertThat(error).hasMessageContaining("count");
    }

    @Test
    @DisplayName("Division by zero is an evaluation error")
    void divisionByZeroIsAnEvaluationError() throws Exception {
        assertThat(evaluationFailure("self.count / (self.count - self.count)", ValueType.INT,
                Map.of("count", Value.of(7))).getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("An evaluation that takes more than 10,000 steps is an evaluation error that names the derivation")
    void anEvaluationThatTakesMoreThanTenThousandStepsIsAnError() throws Exception {
        String ten = "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]";
        String cheap = "size(" + ten + ".filter(a, " + ten + ".all(b, a + b > 0)))";
        String costly = "size(" + ten + ".filter(a, " + ten + ".all(b, " + ten + ".all(c, " + ten
                + ".all(d, a + b + c + d > 0)))))";

        assertThat(derive(cheap, ValueType.INT, Map.of())).contains(Value.of(10));
        assertThat(evaluationFailure(costly, ValueType.INT, Map.of()))
                .hasMessageContaining("derivation").hasMessageContaining("10000").hasMessageContaining("steps");
    }

    @Test
    @DisplayName("A compiled derivation is evaluated by several threads at once")
    void aCompiledDerivationIsEvaluatedByManyThreads() throws Exception {
        CompiledDerivation derivation = compile("self.count + self.big", ValueType.LONG);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<Value>>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                results.add(pool.submit(() -> derivation.evaluate(Map.of("count", Value.of(n),
                        "big", Value.of(1_000L)))));
            }
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).contains(Value.of(1_000L + i));
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Checks and derivations of one language do not disturb each other")
    void checksAndDerivationsOfOneLanguageDoNotDisturbEachOther() throws Exception {
        assertThat(language.compile("self.count > 0", ATTRIBUTES).evaluate(Map.of("count", Value.of(1)))).isTrue();
        assertThat(derive("self.count > 0", ValueType.BOOLEAN, Map.of("count", Value.of(1)))).contains(Value.of(true));
        assertThatThrownBy(() -> language.compile("self.count", ATTRIBUTES))
                .isInstanceOf(ExpressionCompilationException.class).hasMessageContaining("boolean");
    }
}
