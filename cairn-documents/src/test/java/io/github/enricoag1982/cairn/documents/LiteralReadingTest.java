package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Values are converted by the value type of their attribute; what does not fit keeps its own YAML type (design D6). */
class LiteralReadingTest {

    private static List<Value> examples(String type, String examples) {
        return attribute(type, "examples: " + examples).examples();
    }

    private static AttributeDefinition attribute(String type, String properties) {
        return valid(document(
                "entityTypes:",
                "  Thing:",
                "    attributes:",
                "      value: { type: " + type + ", " + properties + " }")).definition().entityTypes().getFirst()
                .attributes().getFirst();
    }

    private static Optional<Range> range(String type, String range) {
        return attribute(type, "range: " + range).range();
    }

    @Test
    void numbersAreReadByTheValueTypeOfTheAttribute() {
        assertThat(examples("int", "[0, 7, -3, 12]")).containsExactly(
                Value.of(0), Value.of(7), Value.of(-3), Value.of(12));
        assertThat(examples("long", "[0, 7, 3000000000, -9223372036854775808]")).containsExactly(
                Value.of(0L), Value.of(7L), Value.of(3000000000L), Value.of(Long.MIN_VALUE));
        assertThat(examples("decimal", "[0, 7, 1.5, -0.25, 0.5, 1e3]")).containsExactly(
                Value.of(new BigDecimal("0")), Value.of(new BigDecimal("7")), Value.of(new BigDecimal("1.5")),
                Value.of(new BigDecimal("-0.25")), Value.of(new BigDecimal("0.5")), Value.of(new BigDecimal("1E+3")));
    }

    @Test
    void hexadecimalAndOctalIntegersAreNoNumbersOfADocument() {
        for (String example : List.of("0x1F", "0o17")) {
            for (String type : List.of("int", "long", "decimal")) {
                Problem problem = problem(document(
                        "entityTypes:",
                        "  Thing:",
                        "    attributes:",
                        "      value: { type: " + type + ", examples: [" + example + "] }"));

                assertThat(problem.code()).as(example + " " + type).isEqualTo(ProblemCode.INVALID_VALUE);
                assertThat(problem.location().orElseThrow().pointer()).as(example + " " + type)
                        .isEqualTo("/entityTypes/Thing/attributes/value/examples/0");
            }
        }
    }

    @Test
    void decimalsNeverPassThroughADouble() {
        String digits = "12345678901234567890.123456789012345678901234567890";

        assertThat(examples("decimal", "[" + digits + "]")).containsExactly(Value.of(new BigDecimal(digits)));
        assertThat(examples("decimal", "[0.10, 0.1]")).containsExactly(
                Value.of(new BigDecimal("0.10")), Value.of(new BigDecimal("0.1")));
        assertThat(Value.of(new BigDecimal("0.10"))).isNotEqualTo(Value.of(new BigDecimal("0.1")));
    }

    @Test
    void booleansAreReadFromPlainTrueAndFalse() {
        assertThat(examples("boolean", "[true, false, True, FALSE]")).containsExactly(
                Value.of(true), Value.of(false), Value.of(true), Value.of(false));
    }

    @Test
    void stringsDatesAndInstantsAreReadFromStrings() {
        assertThat(examples("string", "[abc, 'quoted', \"with: colon\", no, '5']")).containsExactly(
                Value.of("abc"), Value.of("quoted"), Value.of("with: colon"), Value.of("no"), Value.of("5"));
        assertThat(examples("date", "[2026-01-05, \"1999-12-31\"]")).containsExactly(
                Value.of(LocalDate.of(2026, 1, 5)), Value.of(LocalDate.of(1999, 12, 31)));
        assertThat(examples("instant", "[2026-01-05T09:00:00Z, \"2026-01-05T09:00:00.123456Z\"]")).containsExactly(
                Value.of(Instant.parse("2026-01-05T09:00:00Z")), Value.of(Instant.parse("2026-01-05T09:00:00.123456Z")));
    }

    @Test
    void aScalarThatDoesNotFitKeepsItsOwnYamlType() {
        assertThat(examples("date", "[tomorrow, 5, true, 2026-13-45, 1.5]")).containsExactly(
                Value.of("tomorrow"), Value.of(5), Value.of(true), Value.of("2026-13-45"),
                Value.of(new BigDecimal("1.5")));
        assertThat(examples("instant", "[2026-01-05]")).containsExactly(Value.of("2026-01-05"));
        assertThat(examples("string", "[5, true, 1.5]")).containsExactly(
                Value.of(5), Value.of(true), Value.of(new BigDecimal("1.5")));
        assertThat(examples("boolean", "[yes, 1, 'true']")).containsExactly(
                Value.of("yes"), Value.of(1), Value.of("true"));
        assertThat(examples("int", "[1.5, '5', abc, true]")).containsExactly(
                Value.of(new BigDecimal("1.5")), Value.of("5"), Value.of("abc"), Value.of(true));
        assertThat(examples("decimal", "['1.5', abc, false]")).containsExactly(
                Value.of("1.5"), Value.of("abc"), Value.of(false));
    }

    @Test
    void anIntegerThatDoesNotFitTheValueTypeIsALongOrADecimal() {
        assertThat(examples("int", "[3000000000, 99999999999999999999]")).containsExactly(
                Value.of(3000000000L), Value.of(new BigDecimal("99999999999999999999")));
        assertThat(examples("long", "[99999999999999999999, 5]")).containsExactly(
                Value.of(new BigDecimal("99999999999999999999")), Value.of(5L));
        assertThat(examples("string", "[3000000000]")).containsExactly(Value.of(3000000000L));
    }

    @Test
    void numbersThatYamlDoesNotKnowAreStrings() {
        assertThat(examples("decimal", "[1_000, 0b11, '+']")).containsExactly(
                Value.of("1_000"), Value.of("0b11"), Value.of("+"));
    }

    @Test
    void rangeBoundsAreReadByTheValueTypeToo() {
        assertThat(range("decimal", "{ greaterThan: 0, atMost: 100 }")).contains(Range.between(
                Bound.exclusive(Value.of(new BigDecimal("0"))), Bound.inclusive(Value.of(new BigDecimal("100")))));
        assertThat(range("int", "{ atLeast: 1 }")).contains(Range.atLeast(Bound.inclusive(Value.of(1))));
        assertThat(range("long", "{ lessThan: 10 }")).contains(Range.atMost(Bound.exclusive(Value.of(10L))));
        assertThat(range("date", "{ atLeast: 2026-01-01, lessThan: 2027-01-01 }")).contains(Range.between(
                Bound.inclusive(Value.of(LocalDate.of(2026, 1, 1))), Bound.exclusive(Value.of(LocalDate.of(2027, 1, 1)))));
        assertThat(range("instant", "{ atMost: 2026-01-05T09:00:00Z }")).contains(
                Range.atMost(Bound.inclusive(Value.of(Instant.parse("2026-01-05T09:00:00Z")))));
    }

    @Test
    void aBoundThatDoesNotFitIsLeftToValidation() {
        assertThat(range("date", "{ atLeast: 1 }")).contains(Range.atLeast(Bound.inclusive(Value.of(1))));
        assertThat(range("int", "{ atLeast: 1.5 }")).contains(
                Range.atLeast(Bound.inclusive(Value.of(new BigDecimal("1.5")))));
        assertThat(range("string", "{ atLeast: abc }")).contains(Range.atLeast(Bound.inclusive(Value.of("abc"))));
        assertThat(range("boolean", "{ atMost: true }")).contains(Range.atMost(Bound.inclusive(Value.of(true))));
    }

    @Test
    void aRangeMayHaveNoBounds() {
        assertThat(range("int", "{}")).contains(new Range(Optional.empty(), Optional.empty()));
    }

    @Test
    void nullAndInfinityAreNoLiterals() {
        for (String example : List.of("null", "~", ".inf", "-.inf", ".nan")) {
            Problem problem = problem(document(
                    "entityTypes:",
                    "  Thing:",
                    "    attributes:",
                    "      value: { type: decimal, examples: [" + example + "] }"));

            assertThat(problem.code()).as(example).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.path()).as(example).isEqualTo("Thing.value");
            assertThat(problem.location().orElseThrow().pointer()).as(example)
                    .isEqualTo("/entityTypes/Thing/attributes/value/examples/0");
        }
    }

    @Test
    void anExampleThatIsNoScalarIsAProblem() {
        Problem problem = problem(document(
                "entityTypes:",
                "  Thing:",
                "    attributes:",
                "      value: { type: string, examples: [[a], b] }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(problem.found()).contains("a list");
        assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Thing/attributes/value/examples/0");
    }

    @Test
    void literalsOfAnAttributeWithoutAValidTypeAreReadByTheirOwnType() {
        List<Problem> problems = Documents.problems(document(
                "entityTypes:",
                "  Thing:",
                "    attributes:",
                "      value: { type: number, examples: [1, abc], range: { atLeast: 0 } }"));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_VALUE);
            assertThat(problem.location().orElseThrow().pointer()).isEqualTo("/entityTypes/Thing/attributes/value/type");
        });
    }
}
