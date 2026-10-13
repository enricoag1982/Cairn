package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Literals are converted by the value type of their attribute; what does not fit keeps its own YAML type (design D6). */
class LiteralReadingTest {

    private static List<Literal> examples(String type, String examples) {
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
        assertThat(examples("int", "[0, 7, -3, +5]")).containsExactly(
                Literal.of(0), Literal.of(7), Literal.of(-3), Literal.of(5));
        assertThat(examples("long", "[0, 7, 3000000000, -9223372036854775808]")).containsExactly(
                Literal.of(0L), Literal.of(7L), Literal.of(3000000000L), Literal.of(Long.MIN_VALUE));
        assertThat(examples("decimal", "[0, 7, 1.5, -0.25, .5, 1e3]")).containsExactly(
                Literal.of(new BigDecimal("0")), Literal.of(new BigDecimal("7")), Literal.of(new BigDecimal("1.5")),
                Literal.of(new BigDecimal("-0.25")), Literal.of(new BigDecimal("0.5")), Literal.of(new BigDecimal("1E+3")));
    }

    @Test
    void hexadecimalAndOctalIntegersAreIntegers() {
        assertThat(examples("int", "[0x1F, 0o17]")).containsExactly(Literal.of(31), Literal.of(15));
        assertThat(examples("decimal", "[0x10]")).containsExactly(Literal.of(new BigDecimal("16")));
    }

    @Test
    void decimalsNeverPassThroughADouble() {
        String digits = "12345678901234567890.123456789012345678901234567890";

        assertThat(examples("decimal", "[" + digits + "]")).containsExactly(Literal.of(new BigDecimal(digits)));
        assertThat(examples("decimal", "[0.10, 0.1]")).containsExactly(
                Literal.of(new BigDecimal("0.10")), Literal.of(new BigDecimal("0.1")));
        assertThat(Literal.of(new BigDecimal("0.10"))).isNotEqualTo(Literal.of(new BigDecimal("0.1")));
    }

    @Test
    void booleansAreReadFromPlainTrueAndFalse() {
        assertThat(examples("boolean", "[true, false, True, FALSE]")).containsExactly(
                Literal.of(true), Literal.of(false), Literal.of(true), Literal.of(false));
    }

    @Test
    void stringsDatesAndInstantsAreReadFromStrings() {
        assertThat(examples("string", "[abc, 'quoted', \"with: colon\", no, '5']")).containsExactly(
                Literal.of("abc"), Literal.of("quoted"), Literal.of("with: colon"), Literal.of("no"), Literal.of("5"));
        assertThat(examples("date", "[2026-01-05, \"1999-12-31\"]")).containsExactly(
                Literal.of(LocalDate.of(2026, 1, 5)), Literal.of(LocalDate.of(1999, 12, 31)));
        assertThat(examples("instant", "[2026-01-05T09:00:00Z, \"2026-01-05T09:00:00.123456Z\"]")).containsExactly(
                Literal.of(Instant.parse("2026-01-05T09:00:00Z")), Literal.of(Instant.parse("2026-01-05T09:00:00.123456Z")));
    }

    @Test
    void aScalarThatDoesNotFitKeepsItsOwnYamlType() {
        assertThat(examples("date", "[tomorrow, 5, true, 2026-13-45, 1.5]")).containsExactly(
                Literal.of("tomorrow"), Literal.of(5), Literal.of(true), Literal.of("2026-13-45"),
                Literal.of(new BigDecimal("1.5")));
        assertThat(examples("instant", "[2026-01-05]")).containsExactly(Literal.of("2026-01-05"));
        assertThat(examples("string", "[5, true, 1.5]")).containsExactly(
                Literal.of(5), Literal.of(true), Literal.of(new BigDecimal("1.5")));
        assertThat(examples("boolean", "[yes, 1, 'true']")).containsExactly(
                Literal.of("yes"), Literal.of(1), Literal.of("true"));
        assertThat(examples("int", "[1.5, '5', abc, true]")).containsExactly(
                Literal.of(new BigDecimal("1.5")), Literal.of("5"), Literal.of("abc"), Literal.of(true));
        assertThat(examples("decimal", "['1.5', abc, false]")).containsExactly(
                Literal.of("1.5"), Literal.of("abc"), Literal.of(false));
    }

    @Test
    void anIntegerThatDoesNotFitTheValueTypeIsALongOrADecimal() {
        assertThat(examples("int", "[3000000000, 99999999999999999999]")).containsExactly(
                Literal.of(3000000000L), Literal.of(new BigDecimal("99999999999999999999")));
        assertThat(examples("long", "[99999999999999999999, 5]")).containsExactly(
                Literal.of(new BigDecimal("99999999999999999999")), Literal.of(5L));
        assertThat(examples("string", "[3000000000]")).containsExactly(Literal.of(3000000000L));
    }

    @Test
    void numbersThatYamlDoesNotKnowAreStrings() {
        assertThat(examples("decimal", "[1_000, 0b11, '+']")).containsExactly(
                Literal.of("1_000"), Literal.of("0b11"), Literal.of("+"));
    }

    @Test
    void rangeBoundsAreReadByTheValueTypeToo() {
        assertThat(range("decimal", "{ greaterThan: 0, atMost: 100 }")).contains(Range.between(
                Bound.exclusive(Literal.of(new BigDecimal("0"))), Bound.inclusive(Literal.of(new BigDecimal("100")))));
        assertThat(range("int", "{ atLeast: 1 }")).contains(Range.atLeast(Bound.inclusive(Literal.of(1))));
        assertThat(range("long", "{ lessThan: 10 }")).contains(Range.atMost(Bound.exclusive(Literal.of(10L))));
        assertThat(range("date", "{ atLeast: 2026-01-01, lessThan: 2027-01-01 }")).contains(Range.between(
                Bound.inclusive(Literal.of(LocalDate.of(2026, 1, 1))), Bound.exclusive(Literal.of(LocalDate.of(2027, 1, 1)))));
        assertThat(range("instant", "{ atMost: 2026-01-05T09:00:00Z }")).contains(
                Range.atMost(Bound.inclusive(Literal.of(Instant.parse("2026-01-05T09:00:00Z")))));
    }

    @Test
    void aBoundThatDoesNotFitIsLeftToValidation() {
        assertThat(range("date", "{ atLeast: 1 }")).contains(Range.atLeast(Bound.inclusive(Literal.of(1))));
        assertThat(range("int", "{ atLeast: 1.5 }")).contains(
                Range.atLeast(Bound.inclusive(Literal.of(new BigDecimal("1.5")))));
        assertThat(range("string", "{ atLeast: abc }")).contains(Range.atLeast(Bound.inclusive(Literal.of("abc"))));
        assertThat(range("boolean", "{ atMost: true }")).contains(Range.atMost(Bound.inclusive(Literal.of(true))));
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
