package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a transaction changes (design D2 of {@code add-rule-rechecks}): the stretches of effective time in which the
 * timeline it produces differs from the one it started from, in existence or in values. A boundary that only splits equal
 * values is no difference.
 */
class TimelineDifferencesTest {

    private static final AttributeId NAME = new AttributeId(1);
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant APR = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SEP = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant RECORDED_1 = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant RECORDED_2 = Instant.parse("2026-03-16T09:00:00Z");

    private static Timeline acme() {
        return Timeline.created(JAN, Segment.OPEN, Map.of(NAME, Value.of("ACME")), RECORDED_1, new Basis(1));
    }

    private static Timeline named(Timeline timeline, Instant from, Instant end, String name) {
        return timeline.update(from, end, old -> {
            Map<AttributeId, Value> changed = new HashMap<>(old);
            changed.put(NAME, Value.of(name));
            return changed;
        }, RECORDED_2, new Basis(2));
    }

    private static List<Stretch> stretches(Timeline after, Timeline before) {
        return after.differences(before, false);
    }

    @Test
    void identicalTimelinesDoNotDiffer() {
        assertThat(acme().differences(acme(), false)).isEmpty();
        assertThat(Timeline.EMPTY.differences(Timeline.EMPTY, false)).isEmpty();
    }

    @Test
    void aChangeOfValuesInTheMiddleIsOneStretch() {
        Timeline after = named(acme(), MAR, JUN, "Renamed");

        assertThat(stretches(after, acme())).containsExactly(new Stretch(MAR, JUN));
        assertThat(after.differences(acme(), true)).as("the entity exists in both").isEmpty();
    }

    @Test
    void anOpenChangeOfValuesIsAStretchWithoutAnEnd() {
        Timeline after = named(acme(), MAR, Segment.OPEN, "Renamed");

        assertThat(stretches(after, acme())).containsExactly(new Stretch(MAR, Segment.OPEN));
    }

    @Test
    void aTimelineThatIsCreatedDiffersWhereverItExists() {
        assertThat(stretches(acme(), Timeline.EMPTY)).containsExactly(new Stretch(JAN, Segment.OPEN));
        assertThat(acme().differences(Timeline.EMPTY, true)).containsExactly(new Stretch(JAN, Segment.OPEN));
    }

    @Test
    void anEndedTimelineDiffersFromTheEndOnward() {
        Timeline after = acme().endAt(JUN);

        assertThat(stretches(after, acme())).containsExactly(new Stretch(JUN, Segment.OPEN));
        assertThat(after.differences(acme(), true)).containsExactly(new Stretch(JUN, Segment.OPEN));
    }

    @Test
    void anEndInThePastOfTheLifetimeDiffersOnlyWhereTheEntityExistedBefore() {
        Timeline before = named(acme(), MAR, APR, "Spring").endAt(SEP);
        Timeline after = before.endAt(JUN);

        assertThat(stretches(after, before)).containsExactly(new Stretch(JUN, SEP));
    }

    @Test
    void valuesThatAreSetToWhatTheyAreDoNotDiffer() {
        Timeline after = named(acme(), MAR, JUN, "ACME");

        assertThat(stretches(after, acme())).isEmpty();
    }

    @Test
    void aSplitThatTwoRecordingsMadeWithoutChangingValuesDoesNotDiffer() {
        Timeline split = named(named(acme(), MAR, JUN, "Temporary"), MAR, JUN, "ACME");

        assertThat(split.segments()).as("the recordings keep the segments apart").hasSizeGreaterThan(1);
        assertThat(stretches(split, acme())).isEmpty();
        assertThat(stretches(acme(), split)).isEmpty();
    }

    @Test
    void stretchesThatTouchAreMergedAndStretchesThatDoNotStayApart() {
        Timeline renamed = named(acme(), MAR, APR, "March");
        Timeline twice = named(renamed, APR, JUN, "April");
        Timeline apart = named(renamed, JUN, SEP, "Summer");

        assertThat(stretches(twice, acme())).as("two names, one after the other").containsExactly(
                new Stretch(MAR, JUN));
        assertThat(stretches(apart, acme())).containsExactly(new Stretch(MAR, APR), new Stretch(JUN, SEP));
    }

    @Test
    void valuesAndExistenceDifferTogether() {
        Timeline before = acme().endAt(JUN);
        Timeline after = named(acme(), MAR, Segment.OPEN, "Renamed");

        assertThat(stretches(after, before)).as("renamed in March, and alive from June too")
                .containsExactly(new Stretch(MAR, Segment.OPEN));
        assertThat(after.differences(before, true)).as("it exists from June in one only")
                .containsExactly(new Stretch(JUN, Segment.OPEN));
    }

    @Test
    void theComparisonIsTheSameBothWays() {
        Timeline after = named(acme(), MAR, JUN, "Renamed").endAt(SEP);

        assertThat(stretches(after, acme())).isEqualTo(stretches(acme(), after));
    }
}
