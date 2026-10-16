package io.github.enricoag1982.cairn.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionEvaluationException;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The functions of Cairn's own in expressions: {@code cairn.calendarDays} and {@code cairn.workingDays} count by the
 * dates in UTC, both included, in checks and in derivations (ADR-0035, design D1 and D2 of
 * {@code add-expression-functions}).
 */
class CairnFunctionsTest {

    private static final Map<String, ValueType> ATTRIBUTES = Map.of(
            "name", ValueType.STRING,
            "firstDay", ValueType.DATE,
            "lastDay", ValueType.DATE,
            "seenAt", ValueType.INSTANT,
            "count", ValueType.INT);

    private final CelLanguage language = new CelLanguage();

    private static Instant midnight(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Map<String, Value> days(LocalDate first, LocalDate last) {
        return Map.of("firstDay", Value.of(first), "lastDay", Value.of(last));
    }

    /** What a derivation of one of the functions, {@code calendarDays} or {@code workingDays}, gives for two days. */
    private long derive(String function, LocalDate first, LocalDate last) throws Exception {
        Optional<Value> result = language.compileDerivation("cairn." + function + "(self.firstDay, self.lastDay)",
                ATTRIBUTES, ValueType.LONG).evaluate(days(first, last));
        return ((Value.LongValue) result.orElseThrow()).value();
    }

    private ExpressionCompilationException failure(String expression) {
        return catchThrowableOfType(ExpressionCompilationException.class,
                () -> language.compile(expression, ATTRIBUTES));
    }

    /** The count that the functions must give: one day after the other. */
    private static long bruteForce(LocalDate first, LocalDate last, boolean onlyWorkingDays) {
        long count = 0;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (!onlyWorkingDays || day.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) <= 0) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ the scenarios of the delta spec, in the language

    @Test
    @DisplayName("Working days across a weekend are counted from Monday to Friday")
    void workingDaysAcrossAWeekend() throws Exception {
        LocalDate friday = LocalDate.of(2025, 12, 26);
        LocalDate wednesday = LocalDate.of(2025, 12, 31);

        assertThat(friday.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(derive("workingDays", friday, wednesday)).as("26, 29, 30 and 31 December").isEqualTo(4);
        assertThat(derive("calendarDays", friday, wednesday)).isEqualTo(6);
    }

    @Test
    @DisplayName("A last day before the first is 0 days, whichever the function")
    void aLastDayBeforeTheFirstIsZero() throws Exception {
        LocalDate friday = LocalDate.of(2026, 1, 2);
        LocalDate wednesday = LocalDate.of(2025, 12, 31);

        assertThat(derive("workingDays", friday, wednesday)).isZero();
        assertThat(derive("calendarDays", friday, wednesday)).isZero();
        assertThat(derive("workingDays", LocalDate.of(9999, 12, 31), LocalDate.of(1, 1, 1))).isZero();
    }

    // ------------------------------------------------------------------ the counts

    @Test
    @DisplayName("The same day is one day, or none for working days on a weekend")
    void theSameDay() throws Exception {
        LocalDate friday = LocalDate.of(2025, 12, 26);
        LocalDate saturday = LocalDate.of(2025, 12, 27);
        LocalDate sunday = LocalDate.of(2025, 12, 28);
        LocalDate monday = LocalDate.of(2025, 12, 29);

        assertThat(derive("calendarDays", friday, friday)).isEqualTo(1);
        assertThat(derive("workingDays", friday, friday)).isEqualTo(1);
        assertThat(derive("workingDays", monday, monday)).isEqualTo(1);
        assertThat(derive("calendarDays", saturday, saturday)).isEqualTo(1);
        assertThat(derive("workingDays", saturday, saturday)).isZero();
        assertThat(derive("workingDays", sunday, sunday)).isZero();
    }

    @Test
    @DisplayName("A weekend at either end, or at both, is not counted as working days")
    void weekendsAtBothEnds() throws Exception {
        LocalDate friday = LocalDate.of(2025, 12, 26);
        LocalDate saturday = LocalDate.of(2025, 12, 27);
        LocalDate sunday = LocalDate.of(2025, 12, 28);
        LocalDate monday = LocalDate.of(2025, 12, 29);
        LocalDate friday2 = LocalDate.of(2026, 1, 2);
        LocalDate saturday2 = LocalDate.of(2026, 1, 3);
        LocalDate sunday2 = LocalDate.of(2026, 1, 4);

        assertThat(derive("workingDays", saturday, friday2)).as("starting on a Saturday").isEqualTo(5);
        assertThat(derive("workingDays", sunday, friday2)).as("starting on a Sunday").isEqualTo(5);
        assertThat(derive("workingDays", monday, saturday2)).as("ending on a Saturday").isEqualTo(5);
        assertThat(derive("workingDays", monday, sunday2)).as("ending on a Sunday").isEqualTo(5);
        assertThat(derive("workingDays", saturday, sunday2)).as("from a Saturday to a Sunday").isEqualTo(5);
        assertThat(derive("workingDays", saturday, sunday)).as("a weekend alone").isZero();
        assertThat(derive("workingDays", friday, monday)).as("Friday to Monday").isEqualTo(2);
        assertThat(derive("calendarDays", saturday, sunday2)).isEqualTo(9);
    }

    @Test
    @DisplayName("Every start on a weekday and every span up to a month are counted as one day after the other")
    void everyStartAndSpanUpToAMonth() throws Exception {
        LocalDate start = LocalDate.of(2025, 12, 1);
        for (int offset = 0; offset < 14; offset++) {
            LocalDate first = start.plusDays(offset);
            for (int span = 0; span < 40; span++) {
                LocalDate last = first.plusDays(span);

                assertThat(CairnFunctions.workingDays(midnight(first), midnight(last))).as("working %s to %s", first, last)
                        .isEqualTo(bruteForce(first, last, true));
                assertThat(CairnFunctions.calendarDays(midnight(first), midnight(last)))
                        .as("calendar %s to %s", first, last).isEqualTo(bruteForce(first, last, false));
            }
        }
    }

    @Test
    @DisplayName("A span of many years is counted as one day after the other")
    void aSpanOfManyYears() throws Exception {
        for (LocalDate[] span : new LocalDate[][] {
                {LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31)},
                {LocalDate.of(1999, 12, 31), LocalDate.of(2400, 3, 1)},
                {LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28)},
                {LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)}}) {
            assertThat(derive("workingDays", span[0], span[1])).as("working %s to %s", span[0], span[1])
                    .isEqualTo(bruteForce(span[0], span[1], true));
            assertThat(derive("calendarDays", span[0], span[1])).as("calendar %s to %s", span[0], span[1])
                    .isEqualTo(bruteForce(span[0], span[1], false));
        }
    }

    @Test
    @DisplayName("A day is counted by its date in UTC, whatever the time of day")
    void aDayIsCountedByItsDateInUtc() throws Exception {
        // Friday 26 December 2025 ends at 23:59:59 UTC; 27 December, a Saturday, starts a second later.
        Instant lastSecondOfFriday = Instant.parse("2025-12-26T23:59:59.999999999Z");
        Instant firstSecondOfSaturday = Instant.parse("2025-12-27T00:00:00Z");
        Instant wednesdayNoon = Instant.parse("2025-12-31T12:00:00Z");

        assertThat(CairnFunctions.workingDays(lastSecondOfFriday, wednesdayNoon)).isEqualTo(4);
        assertThat(CairnFunctions.workingDays(firstSecondOfSaturday, wednesdayNoon)).isEqualTo(3);
        assertThat(CairnFunctions.calendarDays(lastSecondOfFriday, firstSecondOfSaturday)).isEqualTo(2);
        assertThat(CairnFunctions.calendarDays(Instant.parse("2025-12-26T00:00:00Z"), lastSecondOfFriday))
                .as("the same day").isEqualTo(1);

        Map<String, Value> values = Map.of("seenAt", Value.of(Instant.parse("2025-12-26T23:30:00Z")));
        long viaExpression = ((Value.LongValue) language.compileDerivation(
                "cairn.workingDays(self.seenAt, timestamp('2025-12-31T00:00:00Z'))", ATTRIBUTES, ValueType.LONG)
                .evaluate(values).orElseThrow()).value();
        assertThat(viaExpression).isEqualTo(4);
    }

    @Test
    @DisplayName("A timestamp with an offset is counted by its date in UTC")
    void aTimestampWithAnOffsetIsCountedByItsDateInUtc() throws Exception {
        // 01:00 on Saturday 27 December at +02:00 is 23:00 UTC on Friday 26 December.
        String expression = "cairn.workingDays(timestamp('2025-12-27T01:00:00+02:00'), timestamp('2025-12-27T23:00:00Z'))";

        assertThat(language.compileDerivation(expression, ATTRIBUTES, ValueType.INT).evaluate(Map.of()))
                .contains(Value.of(1));
        assertThat(language.compileDerivation(
                "cairn.workingDays(timestamp('2025-12-26T23:00:00-05:00'), timestamp('2025-12-27T23:00:00Z'))",
                ATTRIBUTES, ValueType.INT).evaluate(Map.of())).as("04:00 UTC on Saturday, so none")
                .contains(Value.of(0));
    }

    @Test
    @DisplayName("The count does not depend on the default time zone")
    void theCountDoesNotDependOnTheDefaultTimeZone() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago", "America/Los_Angeles",
                    "Asia/Kolkata"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));

                assertThat(derive("workingDays", LocalDate.of(2025, 12, 26), LocalDate.of(2025, 12, 31))).as(zone)
                        .isEqualTo(4);
                assertThat(derive("calendarDays", LocalDate.of(2025, 12, 26), LocalDate.of(2025, 12, 31))).as(zone)
                        .isEqualTo(6);
                assertThat(CairnFunctions.workingDays(Instant.parse("2025-12-26T23:59:59Z"),
                        Instant.parse("2025-12-27T00:00:00Z"))).as(zone).isEqualTo(1);
                assertThat(CairnFunctions.calendarDays(Instant.parse("2025-12-26T23:59:59Z"),
                        Instant.parse("2025-12-27T00:00:00Z"))).as(zone).isEqualTo(2);
            }
        } finally {
            TimeZone.setDefault(before);
        }
        assertThat(TimeZone.getDefault()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ in checks and derivations

    @Test
    @DisplayName("A check calls the functions and compares their integers")
    void aCheckCallsTheFunctions() throws Exception {
        String expression = "cairn.workingDays(self.firstDay, self.lastDay) <= 5"
                + " && cairn.calendarDays(self.firstDay, self.lastDay) >= cairn.workingDays(self.firstDay, self.lastDay)";

        assertThat(language.compile(expression, ATTRIBUTES)
                .evaluate(days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20)))).isTrue();
        assertThat(language.compile(expression, ATTRIBUTES)
                .evaluate(days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 24)))).isFalse();
        assertThat(language.compile("cairn.workingDays(self.firstDay, self.lastDay) == 21", ATTRIBUTES)
                .evaluate(days(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 31)))).as("HR-7").isTrue();
    }

    @Test
    @DisplayName("A derivation gives an int, a long, or a decimal through double()")
    void aDerivationGivesAnIntALongOrADecimal() throws Exception {
        Map<String, Value> values = days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20));

        assertThat(language.compileDerivation("cairn.workingDays(self.firstDay, self.lastDay)", ATTRIBUTES,
                ValueType.INT).evaluate(values)).contains(Value.of(5));
        assertThat(language.compileDerivation("cairn.workingDays(self.firstDay, self.lastDay)", ATTRIBUTES,
                ValueType.LONG).evaluate(values)).contains(Value.of(5L));
        assertThat(language.compileDerivation("double(cairn.workingDays(self.firstDay, self.lastDay))", ATTRIBUTES,
                ValueType.DECIMAL).evaluate(values)).contains(Value.of(new BigDecimal("5.0")));
        assertThat(language.compileDerivation("cairn.calendarDays(self.firstDay, self.lastDay) * 2 + 1", ATTRIBUTES,
                ValueType.INT).evaluate(values)).as("an integer like any other").contains(Value.of(11));
    }

    @Test
    @DisplayName("A derivation that depends on an optional day guards it with has()")
    void aDerivationThatDependsOnAnOptionalDayGuardsIt() throws Exception {
        String expression = "has(self.firstDay) ? dyn(cairn.workingDays(self.firstDay, "
                + "timestamp('2025-12-31T00:00:00Z'))) : null";

        assertThat(language.compileDerivation(expression, ATTRIBUTES, ValueType.INT)
                .evaluate(Map.of("firstDay", Value.of(LocalDate.of(2025, 12, 26))))).contains(Value.of(4));
        assertThat(language.compileDerivation(expression, ATTRIBUTES, ValueType.INT).evaluate(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Reading a day that has no value is an evaluation error, as for any expression")
    void readingADayThatHasNoValueIsAnEvaluationError() throws Exception {
        assertThatThrownBy(() -> language.compile("cairn.workingDays(self.firstDay, self.lastDay) > 0", ATTRIBUTES)
                .evaluate(Map.of("lastDay", Value.of(LocalDate.of(2026, 1, 1)))))
                .isInstanceOf(ExpressionEvaluationException.class).hasMessageContaining("firstDay");
    }

    @Test
    @DisplayName("The functions are available for every set of attributes, also none")
    void theFunctionsAreAvailableForEverySetOfAttributes() throws Exception {
        assertThat(language.compile("cairn.workingDays(timestamp('2025-12-26T00:00:00Z'),"
                + " timestamp('2025-12-31T00:00:00Z')) == 4", Map.of()).evaluate(Map.of())).isTrue();
        assertThat(language.compileDerivation("cairn.calendarDays(timestamp('2025-12-26T00:00:00Z'),"
                + " timestamp('2025-12-31T00:00:00Z'))", Map.of(), ValueType.INT).evaluate(Map.of()))
                .contains(Value.of(6));
    }

    // ------------------------------------------------------------------ what does not compile

    @Test
    @DisplayName("A call with arguments of other types does not compile")
    void aCallWithArgumentsOfOtherTypesDoesNotCompile() {
        for (String call : new String[] {
                "cairn.workingDays(self.name, self.firstDay) > 0",
                "cairn.workingDays(self.firstDay, self.name) > 0",
                "cairn.calendarDays(self.count, self.count) > 0",
                "cairn.calendarDays(1, 2) > 0",
                "cairn.calendarDays('2026-01-01', '2026-01-02') > 0",
                "cairn.workingDays(self.firstDay) > 0",
                "cairn.workingDays(self.firstDay, self.lastDay, self.lastDay) > 0",
                "cairn.workingDays() > 0",
                "cairn.workingDays(duration('1h'), duration('1h')) > 0"}) {
            ExpressionCompilationException error = failure(call);

            assertThat(error.getMessage()).as(call).contains("cairn.");
            assertThat(error.line()).as(call).hasValue(1);
        }
    }

    @Test
    @DisplayName("The error of a call with other arguments names the function and gives the position")
    void theErrorOfACallWithOtherArgumentsNamesTheFunction() {
        ExpressionCompilationException error = failure("self.count > 0 && cairn.workingDays(self.name, self.firstDay) > 0");

        assertThat(error.getMessage()).contains("cairn.workingDays").contains("string").contains("Timestamp");
        assertThat(error.line()).hasValue(1);
        assertThat(error.column()).as("the opening parenthesis of the call").hasValue(36);
    }

    @Test
    @DisplayName("A derivation with other arguments, or of another result type, does not compile")
    void aDerivationWithOtherArgumentsOrAnotherResultTypeDoesNotCompile() {
        assertThat(catchThrowableOfType(ExpressionCompilationException.class, () -> language.compileDerivation(
                "cairn.workingDays(self.name, self.firstDay)", ATTRIBUTES, ValueType.INT)).getMessage())
                .contains("cairn.workingDays");
        assertThat(catchThrowableOfType(ExpressionCompilationException.class, () -> language.compileDerivation(
                "cairn.workingDays(self.firstDay, self.lastDay)", ATTRIBUTES, ValueType.DECIMAL)).getMessage())
                .as("CEL does not convert an int to a double").contains("double").contains("int");
        assertThat(catchThrowableOfType(ExpressionCompilationException.class, () -> language.compileDerivation(
                "cairn.workingDays(self.firstDay, self.lastDay)", ATTRIBUTES, ValueType.STRING)).getMessage())
                .contains("string").contains("int");
    }

    @Test
    @DisplayName("A function of Cairn's that the language does not offer does not compile")
    void aFunctionThatTheLanguageDoesNotOfferDoesNotCompile() {
        assertThat(failure("cairn.hours(self.firstDay, self.lastDay) > 0").getMessage()).contains("undeclared reference")
                .contains("cairn");
        assertThat(failure("cairn.workingdays(self.firstDay, self.lastDay) > 0").getMessage())
                .as("names are case sensitive").contains("undeclared reference").contains("cairn");
        assertThat(failure("workingDays(self.firstDay, self.lastDay) > 0").getMessage())
                .as("the name is qualified").contains("workingDays");
        assertThat(failure("self.firstDay.workingDays(self.lastDay) > 0").getMessage())
                .as("it is not a method").contains("workingDays");
        assertThat(failure("cairn.workingDays > 0").getMessage()).as("it is not a value").isNotBlank();
    }

    @Test
    @DisplayName("An argument of unknown type compiles, and a wrong one is an evaluation error")
    void anArgumentOfUnknownTypeCompilesAndAWrongOneIsAnEvaluationError() throws Exception {
        assertThat(language.compile("cairn.workingDays(dyn(self.firstDay), self.lastDay) == 5", ATTRIBUTES)
                .evaluate(days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20)))).isTrue();
        assertThatThrownBy(() -> language.compile("cairn.workingDays(dyn('x'), self.lastDay) > 0", ATTRIBUTES)
                .evaluate(days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20))))
                .isInstanceOf(ExpressionEvaluationException.class).hasMessageContaining("cairn.workingDays");
    }

    // ------------------------------------------------------------------ cost

    @Test
    @DisplayName("A call takes one evaluation step with its arguments, however long the span")
    void aCallTakesOneEvaluationStepWithItsArguments() throws Exception {
        String ten = "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]";
        String widest = "cairn.workingDays(timestamp('0001-01-01T00:00:00Z'), timestamp('9999-12-31T00:00:00Z'))";
        long expected = bruteForce(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), true);

        assertThat(language.compile(widest + " == " + expected, Map.of()).evaluate(Map.of())).isTrue();
        assertThat(language.compile(ten + ".all(a, " + widest + " > a)", Map.of()).evaluate(Map.of()))
                .as("ten calls, about a hundred steps").isTrue();
    }

    @Test
    @DisplayName("Calls count toward the limit of 10,000 evaluation steps")
    void callsCountTowardTheLimit() throws Exception {
        String ten = "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]";
        String call = "cairn.workingDays(self.firstDay, self.lastDay) > a + b - 100";
        String cheap = ten + ".all(a, " + ten + ".all(b, " + call + "))";
        String costly = ten + ".all(a, " + ten + ".all(b, " + ten + ".all(c, " + call + ")))";
        Map<String, Value> values = days(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20));

        assertThat(language.compile(cheap, ATTRIBUTES).evaluate(values)).as("a hundred calls").isTrue();
        assertThatThrownBy(() -> language.compile(costly, ATTRIBUTES).evaluate(values))
                .as("a thousand calls")
                .isInstanceOf(ExpressionEvaluationException.class)
                .hasMessageContaining("10000").hasMessageContaining("steps");
    }

    @Test
    @DisplayName("Working days never exceed calendar days")
    void workingDaysNeverExceedCalendarDays() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        for (int day = -3; day < 400; day++) {
            Instant last = first.plusSeconds(day * 86_400L + 3_600L);

            assertThat(CairnFunctions.workingDays(first, last))
                    .isLessThanOrEqualTo(CairnFunctions.calendarDays(first, last));
        }
    }
}
