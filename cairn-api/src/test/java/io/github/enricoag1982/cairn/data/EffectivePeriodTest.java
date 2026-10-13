package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EffectivePeriodTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN_1 = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void truncatesBothInstantsToMicroseconds() {
        EffectivePeriod period = EffectivePeriod.between(
                Instant.parse("2026-01-01T10:15:30.123456789Z"), Instant.parse("2026-06-01T10:15:30.987654321Z"));

        assertThat(period.from()).isEqualTo(Instant.parse("2026-01-01T10:15:30.123456Z"));
        assertThat(period.until()).contains(Instant.parse("2026-06-01T10:15:30.987654Z"));
    }

    @Test
    void periodsWithEqualMicrosecondsAreEqual() {
        assertThat(EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00.000001999Z")))
                .isEqualTo(EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00.000001Z")));
    }

    @Test
    void fromHasNoEnd() {
        EffectivePeriod period = EffectivePeriod.from(JAN_1);

        assertThat(period.from()).isEqualTo(JAN_1);
        assertThat(period.until()).isEmpty();
        assertThat(period.endsAfterStart()).isTrue();
    }

    @Test
    void betweenHasAnEnd() {
        EffectivePeriod period = EffectivePeriod.between(JAN_1, JUN_1);

        assertThat(period).isEqualTo(new EffectivePeriod(JAN_1, Optional.of(JUN_1)));
        assertThat(period.endsAfterStart()).isTrue();
    }

    @Test
    void holdsAPeriodThatEndsBeforeItStartsSoThatTheStoreCanReportIt() {
        EffectivePeriod backwards = EffectivePeriod.between(JUN_1, JAN_1);
        EffectivePeriod empty = EffectivePeriod.between(JAN_1, JAN_1);

        assertThat(backwards.endsAfterStart()).isFalse();
        assertThat(empty.endsAfterStart()).isFalse();
        assertThat(backwards.contains(JAN_1)).isFalse();
        assertThat(empty.contains(JAN_1)).isFalse();
    }

    @Test
    void endsAfterStartLooksAtTheTruncatedInstants() {
        EffectivePeriod period = EffectivePeriod.between(
                Instant.parse("2026-01-01T00:00:00.000001Z"), Instant.parse("2026-01-01T00:00:00.0000019Z"));

        assertThat(period.endsAfterStart()).isFalse();
    }

    @Test
    void isHalfOpen() {
        EffectivePeriod period = EffectivePeriod.between(JAN_1, JUN_1);

        assertThat(period.contains(JAN_1.minusNanos(1_000))).isFalse();
        assertThat(period.contains(JAN_1)).isTrue();
        assertThat(period.contains(JUN_1.minusNanos(1_000))).isTrue();
        assertThat(period.contains(JUN_1)).isFalse();
    }

    @Test
    void aPeriodWithoutAnEndContainsEveryLaterInstant() {
        EffectivePeriod period = EffectivePeriod.from(JAN_1);

        assertThat(period.contains(JAN_1.minusNanos(1_000))).isFalse();
        assertThat(period.contains(JAN_1)).isTrue();
        assertThat(period.contains(Instant.parse("2999-12-31T23:59:59Z"))).isTrue();
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new EffectivePeriod(null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EffectivePeriod(JAN_1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EffectivePeriod.from(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EffectivePeriod.between(JAN_1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EffectivePeriod.between(null, JUN_1)).isInstanceOf(NullPointerException.class);
    }
}
