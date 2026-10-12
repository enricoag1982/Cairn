package io.github.enricoag1982.sysrec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MultiplicityTest {

    @Test
    void constantsCoverTheFourAllowedCombinations() {
        assertThat(Multiplicity.ZERO_OR_ONE).isEqualTo(new Multiplicity(0, 1));
        assertThat(Multiplicity.EXACTLY_ONE).isEqualTo(new Multiplicity(1, 1));
        assertThat(Multiplicity.ZERO_OR_MORE).isEqualTo(new Multiplicity(0, Multiplicity.UNBOUNDED));
        assertThat(Multiplicity.ONE_OR_MORE).isEqualTo(new Multiplicity(1, Multiplicity.UNBOUNDED));
        assertThat(Multiplicity.ONE_OR_MORE.isUnbounded()).isTrue();
        assertThat(Multiplicity.EXACTLY_ONE.isUnbounded()).isFalse();
    }

    @Test
    void minimumMustBeZeroOrOne() {
        assertThatThrownBy(() -> new Multiplicity(2, Multiplicity.UNBOUNDED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Multiplicity(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maximumMustBeOneOrUnbounded() {
        assertThatThrownBy(() -> new Multiplicity(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Multiplicity(0, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printsAsMinimumDotDotMaximum() {
        assertThat(Multiplicity.ZERO_OR_MORE).hasToString("0..*");
        assertThat(Multiplicity.EXACTLY_ONE).hasToString("1..1");
    }
}
