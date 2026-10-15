package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The rule code of a problem (design D4 of {@code add-entity-rules}). */
class ProblemRuleCodeTest {

    private static final DocumentLocation LOCATION = new DocumentLocation("/entityTypes/Organization", 21, 3);

    private static Problem violation() {
        return new Problem(ProblemCode.RULE_VIOLATION, "$gamma.lei", "The check digits are wrong.", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("lei-checksum/invalid-check-digits"));
    }

    @Test
    void everyConstructorWithoutARuleCodeLeavesItEmpty() {
        assertThat(new Problem(ProblemCode.INVALID_NAME, "x", "y").ruleCode()).isEmpty();
        assertThat(new Problem(ProblemCode.INVALID_NAME, "x", "y", Optional.empty(), Optional.empty(),
                Optional.empty()).ruleCode()).isEmpty();
        assertThat(new Problem(ProblemCode.INVALID_NAME, "x", "y", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(LOCATION)).ruleCode()).isEmpty();
    }

    @Test
    void aProblemKeepsItsRuleCodeWhenItIsLocated() {
        Problem located = violation().at(LOCATION);

        assertThat(located.ruleCode()).contains("lei-checksum/invalid-check-digits");
        assertThat(located.location()).contains(LOCATION);
    }

    @Test
    void theRuleCodeIsPartOfEquality() {
        Problem other = new Problem(ProblemCode.RULE_VIOLATION, "$gamma.lei", "The check digits are wrong.");

        assertThat(violation()).isNotEqualTo(other).isEqualTo(violation());
    }

    @Test
    void aMissingRuleCodeIsRejected() {
        assertThatThrownBy(() -> new Problem(ProblemCode.INVALID_NAME, "x", "y", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRuleCodeShowsInTheTextOfTheProblemOnlyIfThereIsOne() {
        assertThat(violation()).hasToString(
                "RULE_VIOLATION (lei-checksum/invalid-check-digits) at $gamma.lei: The check digits are wrong.");
        assertThat(new Problem(ProblemCode.INVALID_NAME, "1Party", "Bad name."))
                .hasToString("INVALID_NAME at 1Party: Bad name.");
    }

    @Test
    void theRuleCodesHaveExplanations() {
        for (ProblemCode code : new ProblemCode[] {ProblemCode.RULE_VIOLATION, ProblemCode.RULE_FAILED,
                ProblemCode.RULE_UNAVAILABLE}) {
            assertThat(code.explanation()).as(code.name()).isNotBlank().endsWith(".");
        }
    }
}
