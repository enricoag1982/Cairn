package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValuesTest {

    @Test
    void stringsAndBooleansFitOnlyTheirOwnValueType() {
        assertThat(Values.fits(Value.of("Corporate"), ValueType.STRING)).isTrue();
        assertThat(Values.fits(Value.of("Corporate"), ValueType.DATE)).isFalse();
        assertThat(Values.fits(Value.of("1"), ValueType.INT)).isFalse();
        assertThat(Values.fits(Value.of(true), ValueType.BOOLEAN)).isTrue();
        assertThat(Values.fits(Value.of(true), ValueType.STRING)).isFalse();
    }

    @Test
    void narrowerNumbersFitWiderOnes() {
        assertThat(Values.fits(Value.of(1), ValueType.LONG)).isTrue();
        assertThat(Values.fits(Value.of(1), ValueType.DECIMAL)).isTrue();
        assertThat(Values.fits(Value.of(1L), ValueType.DECIMAL)).isTrue();
        assertThat(Values.fits(Value.of(1L), ValueType.INT)).isFalse();
        assertThat(Values.fits(Value.of(1), ValueType.STRING)).isFalse();
    }

    @Test
    void aValueIsWithinARangeAccordingToItsBounds() {
        Range range = Range.between(Bound.exclusive(Value.of(0)), Bound.inclusive(Value.of(100)));

        assertThat(Values.within(Value.of(50), range)).isTrue();
        assertThat(Values.within(Value.of(100), range)).isTrue();
        assertThat(Values.within(Value.of(0), range)).isFalse();
        assertThat(Values.within(Value.of(101), range)).isFalse();
        assertThat(Values.within(Value.of(new BigDecimal("0.5")), range)).isTrue();
        assertThat(Values.within(Value.of(new BigDecimal("100.01")), range)).isFalse();
        assertThat(Values.within(Value.of(1_000L), Range.atLeast(Bound.inclusive(Value.of(5))))).isTrue();
        assertThat(Values.within(Value.of(1), Range.atMost(Bound.exclusive(Value.of(1))))).isFalse();
    }

    @Test
    void datesAndInstantsAreWithinARangeToo() {
        Range dates = Range.atLeast(Bound.inclusive(Value.of(LocalDate.of(2026, 1, 1))));
        Range instants = Range.atMost(Bound.exclusive(Value.of(Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(Values.within(Value.of(LocalDate.of(2026, 1, 1)), dates)).isTrue();
        assertThat(Values.within(Value.of(LocalDate.of(2025, 12, 31)), dates)).isFalse();
        assertThat(Values.within(Value.of(Instant.parse("2025-12-31T23:59:59Z")), instants)).isTrue();
        assertThat(Values.within(Value.of(Instant.parse("2026-01-01T00:00:00Z")), instants)).isFalse();
    }

    @Test
    void aBoundOfAnotherKindIsNotHeldAgainstTheValue() {
        Range mismatched = Range.atLeast(Bound.inclusive(Value.of("a")));

        assertThat(Values.within(Value.of(5), mismatched)).isTrue();
    }

    @Test
    void valuesAreShownAsPlainText() {
        assertThat(Values.describe(Value.of("tomorrow"))).isEqualTo("tomorrow");
        assertThat(Values.describe(Value.of(true))).isEqualTo("true");
        assertThat(Values.describe(Value.of(1))).isEqualTo("1");
        assertThat(Values.describe(Value.of(2L))).isEqualTo("2");
        assertThat(Values.describe(Value.of(new BigDecimal("1E+2")))).isEqualTo("100");
        assertThat(Values.describe(Value.of(LocalDate.of(2026, 1, 5)))).isEqualTo("2026-01-05");
        assertThat(Values.describe(Value.of(Instant.parse("2026-01-05T09:00:00Z")))).isEqualTo("2026-01-05T09:00:00Z");
    }

    @Test
    void rangesAreShownAsConditions() {
        assertThat(Values.describe(Range.between(Bound.exclusive(Value.of(0)), Bound.inclusive(Value.of(100)))))
                .isEqualTo("> 0 and <= 100");
        assertThat(Values.describe(Range.atLeast(Bound.inclusive(Value.of(1))))).isEqualTo(">= 1");
        assertThat(Values.describe(Range.atMost(Bound.exclusive(Value.of(9))))).isEqualTo("< 9");
    }

    @Test
    void valueTypesAreShownInLowerCase() {
        assertThat(Values.typeName(ValueType.DECIMAL)).isEqualTo("decimal");
    }
}
