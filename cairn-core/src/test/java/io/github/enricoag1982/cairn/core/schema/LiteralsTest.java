package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LiteralsTest {

    @Test
    void stringsAndBooleansFitOnlyTheirOwnValueType() {
        assertThat(Literals.fits(Literal.of("Corporate"), ValueType.STRING)).isTrue();
        assertThat(Literals.fits(Literal.of("Corporate"), ValueType.DATE)).isFalse();
        assertThat(Literals.fits(Literal.of("1"), ValueType.INT)).isFalse();
        assertThat(Literals.fits(Literal.of(true), ValueType.BOOLEAN)).isTrue();
        assertThat(Literals.fits(Literal.of(true), ValueType.STRING)).isFalse();
    }

    @Test
    void narrowerNumbersFitWiderOnes() {
        assertThat(Literals.fits(Literal.of(1), ValueType.LONG)).isTrue();
        assertThat(Literals.fits(Literal.of(1), ValueType.DECIMAL)).isTrue();
        assertThat(Literals.fits(Literal.of(1L), ValueType.DECIMAL)).isTrue();
        assertThat(Literals.fits(Literal.of(1L), ValueType.INT)).isFalse();
        assertThat(Literals.fits(Literal.of(1), ValueType.STRING)).isFalse();
    }

    @Test
    void aValueIsWithinARangeAccordingToItsBounds() {
        Range range = Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100)));

        assertThat(Literals.within(Literal.of(50), range)).isTrue();
        assertThat(Literals.within(Literal.of(100), range)).isTrue();
        assertThat(Literals.within(Literal.of(0), range)).isFalse();
        assertThat(Literals.within(Literal.of(101), range)).isFalse();
        assertThat(Literals.within(Literal.of(new BigDecimal("0.5")), range)).isTrue();
        assertThat(Literals.within(Literal.of(new BigDecimal("100.01")), range)).isFalse();
        assertThat(Literals.within(Literal.of(1_000L), Range.atLeast(Bound.inclusive(Literal.of(5))))).isTrue();
        assertThat(Literals.within(Literal.of(1), Range.atMost(Bound.exclusive(Literal.of(1))))).isFalse();
    }

    @Test
    void datesAndInstantsAreWithinARangeToo() {
        Range dates = Range.atLeast(Bound.inclusive(Literal.of(LocalDate.of(2026, 1, 1))));
        Range instants = Range.atMost(Bound.exclusive(Literal.of(Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(Literals.within(Literal.of(LocalDate.of(2026, 1, 1)), dates)).isTrue();
        assertThat(Literals.within(Literal.of(LocalDate.of(2025, 12, 31)), dates)).isFalse();
        assertThat(Literals.within(Literal.of(Instant.parse("2025-12-31T23:59:59Z")), instants)).isTrue();
        assertThat(Literals.within(Literal.of(Instant.parse("2026-01-01T00:00:00Z")), instants)).isFalse();
    }

    @Test
    void aBoundOfAnotherKindIsNotHeldAgainstTheValue() {
        Range mismatched = Range.atLeast(Bound.inclusive(Literal.of("a")));

        assertThat(Literals.within(Literal.of(5), mismatched)).isTrue();
    }

    @Test
    void literalsAreShownAsPlainText() {
        assertThat(Literals.describe(Literal.of("tomorrow"))).isEqualTo("tomorrow");
        assertThat(Literals.describe(Literal.of(true))).isEqualTo("true");
        assertThat(Literals.describe(Literal.of(1))).isEqualTo("1");
        assertThat(Literals.describe(Literal.of(2L))).isEqualTo("2");
        assertThat(Literals.describe(Literal.of(new BigDecimal("1E+2")))).isEqualTo("100");
        assertThat(Literals.describe(Literal.of(LocalDate.of(2026, 1, 5)))).isEqualTo("2026-01-05");
        assertThat(Literals.describe(Literal.of(Instant.parse("2026-01-05T09:00:00Z")))).isEqualTo("2026-01-05T09:00:00Z");
    }

    @Test
    void rangesAreShownAsConditions() {
        assertThat(Literals.describe(Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100)))))
                .isEqualTo("> 0 and <= 100");
        assertThat(Literals.describe(Range.atLeast(Bound.inclusive(Literal.of(1))))).isEqualTo(">= 1");
        assertThat(Literals.describe(Range.atMost(Bound.exclusive(Literal.of(9))))).isEqualTo("< 9");
    }

    @Test
    void valueTypesAreShownInLowerCase() {
        assertThat(Literals.typeName(ValueType.DECIMAL)).isEqualTo("decimal");
    }
}
