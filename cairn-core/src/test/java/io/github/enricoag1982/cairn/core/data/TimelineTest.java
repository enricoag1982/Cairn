package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Design D4 and D5: timelines split at the bounds of a change and merge adjacent equal segments. */
class TimelineTest {

    private static final AttributeId NAME = new AttributeId(1);
    private static final AttributeId BALANCE = new AttributeId(2);
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SEP = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant RECORDED_1 = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant RECORDED_2 = Instant.parse("2026-03-16T09:00:00Z");

    private static Map<AttributeId, Value> values(String name, int balance) {
        return Map.of(NAME, Value.of(name), BALANCE, Value.of(balance));
    }

    private static Timeline acme() {
        return Timeline.created(JAN, Segment.OPEN, values("ACME", 1000), RECORDED_1, new Basis(1));
    }

    private static Timeline set(Timeline timeline, Instant from, Instant end, AttributeId attribute, Value value) {
        return timeline.update(from, end, old -> {
            Map<AttributeId, Value> changed = new HashMap<>(old);
            changed.put(attribute, value);
            return changed;
        }, RECORDED_2, new Basis(2));
    }

    @Test
    void aCreatedEntityHasOneSegment() {
        Timeline timeline = acme();

        assertThat(timeline.segments()).hasSize(1);
        assertThat(timeline.segments().getFirst().from()).isEqualTo(JAN);
        assertThat(timeline.segments().getFirst().end()).isEqualTo(Segment.OPEN);
        assertThat(timeline.segments().getFirst().period().until()).isEmpty();
        assertThat(timeline.lifetime()).contains(new Timeline.Lifetime(JAN, Segment.OPEN));
    }

    @Test
    void anOpenChangeSplitsTheSegmentAtItsStart() {
        Timeline timeline = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.segments()).hasSize(2);
        assertThat(timeline.segments().get(0).from()).isEqualTo(JAN);
        assertThat(timeline.segments().get(0).end()).isEqualTo(MAR);
        assertThat(timeline.segments().get(0).values()).isEqualTo(values("ACME", 1000));
        assertThat(timeline.segments().get(1).from()).isEqualTo(MAR);
        assertThat(timeline.segments().get(1).end()).isEqualTo(Segment.OPEN);
        assertThat(timeline.segments().get(1).values()).isEqualTo(values("ACME", 1500));
    }

    @Test
    void aClosedChangeInTheMiddleSplitsTheSegmentInThree() {
        Timeline timeline = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN);
        assertThat(timeline.segments()).extracting(Segment::end).containsExactly(MAR, JUN, Segment.OPEN);
        assertThat(timeline.segments().get(0).values()).isEqualTo(values("ACME", 1000));
        assertThat(timeline.segments().get(1).values()).isEqualTo(values("ACME", 1500));
        assertThat(timeline.segments().get(2).values()).isEqualTo(values("ACME", 1000));
    }

    @Test
    void aChangeOverSeveralSegmentsChangesEachOfThem() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        Timeline timeline = set(split, JAN, SEP, NAME, Value.of("ACME Holdings"));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN, SEP);
        assertThat(timeline.segments().get(0).values()).isEqualTo(values("ACME Holdings", 1000));
        assertThat(timeline.segments().get(1).values()).isEqualTo(values("ACME Holdings", 1500));
        assertThat(timeline.segments().get(2).values()).isEqualTo(values("ACME Holdings", 1000));
        assertThat(timeline.segments().get(3).values()).isEqualTo(values("ACME", 1000));
    }

    @Test
    void adjacentSegmentsWithEqualValuesAreMerged() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        Timeline back = set(split, MAR, Segment.OPEN, BALANCE, Value.of(1000));

        assertThat(back.segments()).hasSize(1);
        assertThat(back.segments().getFirst().from()).isEqualTo(JAN);
        assertThat(back.segments().getFirst().end()).isEqualTo(Segment.OPEN);
    }

    @Test
    void aMergedSegmentKeepsTheEarlierRecording() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        Timeline back = set(split, MAR, Segment.OPEN, BALANCE, Value.of(1000));

        assertThat(back.segments().getFirst().recordedBasis()).isEqualTo(new Basis(1));
        assertThat(back.segments().getFirst().recordedAt()).isEqualTo(RECORDED_1);
    }

    @Test
    void aChangeThatChangesNothingKeepsTheRecordingAndTheTimeline() {
        Timeline timeline = acme();

        Timeline same = set(timeline, MAR, JUN, BALANCE, Value.of(1000));

        assertThat(same).isEqualTo(timeline);
    }

    @Test
    void onlyTheChangedPartIsRecordedByTheNewTransaction() {
        Timeline timeline = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.segments().get(0).recordedBasis()).isEqualTo(new Basis(1));
        assertThat(timeline.segments().get(0).recordedAt()).isEqualTo(RECORDED_1);
        assertThat(timeline.segments().get(1).recordedBasis()).isEqualTo(new Basis(2));
        assertThat(timeline.segments().get(1).recordedAt()).isEqualTo(RECORDED_2);
    }

    @Test
    void aChangeBeyondTheEntityOnlyChangesWhatExists() {
        Timeline closed = Timeline.created(MAR, JUN, values("ACME", 1000), RECORDED_1, new Basis(1));

        Timeline timeline = set(closed, JAN, SEP, BALANCE, Value.of(5));

        assertThat(timeline.segments()).hasSize(1);
        assertThat(timeline.lifetime()).contains(new Timeline.Lifetime(MAR, JUN));
        assertThat(timeline.segments().getFirst().values()).isEqualTo(values("ACME", 5));
    }

    @Test
    void aChangeCanRemoveValues() {
        Timeline timeline = acme().update(MAR, Segment.OPEN, old -> Map.of(NAME, Value.of("ACME")), RECORDED_2,
                new Basis(2));

        assertThat(timeline.segments()).hasSize(2);
        assertThat(timeline.segments().get(1).values()).isEqualTo(Map.of(NAME, Value.of("ACME")));
    }

    @Test
    void endingCutsEverySegmentAtTheInstant() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        Timeline ended = split.endAt(JUN);

        assertThat(ended.segments()).extracting(Segment::from).containsExactly(JAN, MAR);
        assertThat(ended.segments()).extracting(Segment::end).containsExactly(MAR, JUN);
        assertThat(ended.lifetime()).contains(new Timeline.Lifetime(JAN, JUN));
    }

    @Test
    void endingAtTheStartOfASegmentDropsThatSegmentAndTheOnesAfterIt() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        Timeline ended = split.endAt(MAR);

        assertThat(ended.segments()).hasSize(1);
        assertThat(ended.lifetime()).contains(new Timeline.Lifetime(JAN, MAR));
    }

    @Test
    void endingAfterTheEndChangesNothing() {
        Timeline closed = Timeline.created(JAN, JUN, values("ACME", 1000), RECORDED_1, new Basis(1));

        assertThat(closed.endAt(SEP)).isEqualTo(closed);
    }

    @Test
    void endingAtOrBeforeTheStartLeavesNothing() {
        assertThat(acme().endAt(JAN).isEmpty()).isTrue();
        assertThat(acme().endAt(JAN).lifetime()).isEmpty();
    }

    @Test
    void anInstantIsFoundInTheSegmentThatContainsIt() {
        Timeline timeline = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.at(JAN.minusNanos(1000))).isEmpty();
        assertThat(timeline.at(JAN)).map(Segment::values).contains(values("ACME", 1000));
        assertThat(timeline.at(MAR.minusNanos(1000))).map(Segment::values).contains(values("ACME", 1000));
        assertThat(timeline.at(MAR)).map(Segment::values).contains(values("ACME", 1500));
        assertThat(timeline.at(SEP)).map(Segment::values).contains(values("ACME", 1500));
    }

    @Test
    void periodsAreHalfOpen() {
        Timeline closed = Timeline.created(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));

        assertThat(closed.at(JAN)).isPresent();
        assertThat(closed.at(MAR)).isEmpty();
    }

    @Test
    void aTimelineCoversThePeriodsInsideItsLifetime() {
        Timeline closed = Timeline.created(MAR, SEP, values("ACME", 1000), RECORDED_1, new Basis(1));
        Timeline split = set(closed, JUN, Segment.OPEN, BALANCE, Value.of(1));

        assertThat(split.covers(MAR, SEP)).isTrue();
        assertThat(split.covers(MAR, JUN)).isTrue();
        assertThat(split.covers(JUN, SEP)).isTrue();
        assertThat(split.covers(JUN.minusSeconds(1), JUN.plusSeconds(1))).isTrue();
    }

    @Test
    void aTimelineDoesNotCoverPeriodsOutsideItsLifetime() {
        Timeline closed = Timeline.created(MAR, SEP, values("ACME", 1000), RECORDED_1, new Basis(1));

        assertThat(closed.covers(JAN, JUN)).isFalse();
        assertThat(closed.covers(JUN, Segment.OPEN)).isFalse();
        assertThat(closed.covers(SEP, Segment.OPEN)).isFalse();
        assertThat(closed.covers(MAR.minusSeconds(1), MAR.plusSeconds(1))).isFalse();
        assertThat(Timeline.EMPTY.covers(JAN, MAR)).isFalse();
    }

    @Test
    void anOpenEntityCoversAnOpenPeriod() {
        assertThat(acme().covers(MAR, Segment.OPEN)).isTrue();
        assertThat(acme().covers(JAN, Segment.OPEN)).isTrue();
        assertThat(acme().covers(JAN.minusSeconds(1), Segment.OPEN)).isFalse();
    }

    @Test
    void timelinesAreComparedByStateAndNotByRecording() {
        Timeline first = acme();
        Timeline second = Timeline.created(JAN, Segment.OPEN, values("ACME", 1000), RECORDED_2, new Basis(7));

        assertThat(first.sameState(second)).isTrue();
        assertThat(first).isNotEqualTo(second);
        assertThat(first.sameState(set(first, MAR, Segment.OPEN, BALANCE, Value.of(1)))).isFalse();
    }

    @Test
    void anAttributeIsComparedOverTime() {
        Timeline first = acme();
        Timeline renamed = set(first, MAR, Segment.OPEN, NAME, Value.of("ACME Holdings"));
        Timeline split = set(first, MAR, JUN, NAME, Value.of("ACME"));

        assertThat(first.sameValues(renamed, NAME)).isFalse();
        assertThat(first.sameValues(renamed, BALANCE)).isTrue();
        assertThat(first.sameValues(split, NAME)).as("a split with equal values is no change").isTrue();
        assertThat(first.sameValues(Timeline.EMPTY, NAME)).isFalse();
        assertThat(Timeline.EMPTY.sameValues(Timeline.EMPTY, NAME)).isTrue();
    }

    @Test
    void anAttributeWithoutAValueEqualsAMissingEntity() {
        Timeline noBalance = Timeline.created(JAN, Segment.OPEN, Map.of(NAME, Value.of("ACME")), RECORDED_1,
                new Basis(1));

        assertThat(noBalance.sameValues(Timeline.EMPTY, BALANCE)).isTrue();
    }

    @Test
    void buildingFromSegmentsSortsThemAndMergesEqualNeighbours() {
        Segment late = new Segment(MAR, Segment.OPEN, values("ACME", 1000), RECORDED_2, new Basis(2));
        Segment early = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));

        Timeline timeline = Timeline.of(List.of(late, early));

        assertThat(timeline.segments()).hasSize(1);
        assertThat(timeline.segments().getFirst().from()).isEqualTo(JAN);
        assertThat(timeline.segments().getFirst().recordedBasis()).isEqualTo(new Basis(1));
    }

    @Test
    void segmentsWithAGapBetweenThemAreNotMerged() {
        Segment first = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));
        Segment second = new Segment(JUN, SEP, values("ACME", 1000), RECORDED_1, new Basis(1));

        assertThat(Timeline.of(List.of(first, second)).segments()).hasSize(2);
    }

    @Test
    void overlappingSegmentsAreRejected() {
        Segment first = new Segment(JAN, JUN, values("ACME", 1000), RECORDED_1, new Basis(1));
        Segment second = new Segment(MAR, SEP, values("ACME", 5), RECORDED_1, new Basis(1));

        assertThatThrownBy(() -> Timeline.of(List.of(first, second))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSegmentMustEndAfterItStarts() {
        assertThatThrownBy(() -> new Segment(MAR, MAR, Map.of(), RECORDED_1, new Basis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntityAppendsOneTimelinePerBasisAndNeverChangesAnEarlierOne() {
        StoredEntity entity = new StoredEntity(new io.github.enricoag1982.cairn.data.EntityId("e1"),
                new io.github.enricoag1982.cairn.schema.TypeId(1));
        Timeline first = acme();
        Timeline second = set(first, MAR, Segment.OPEN, BALANCE, Value.of(1500));

        entity.append(new Basis(1), first);
        entity.append(new Basis(4), second);

        assertThat(entity.at(Basis.NONE).isEmpty()).isTrue();
        assertThat(entity.at(new Basis(1))).isSameAs(first);
        assertThat(entity.at(new Basis(3))).isSameAs(first);
        assertThat(entity.at(new Basis(4))).isSameAs(second);
        assertThat(entity.at(new Basis(99))).isSameAs(second);
        assertThat(entity.latest()).isSameAs(second);
        assertThat(entity.latestBasis()).isEqualTo(new Basis(4));
        assertThatThrownBy(() -> entity.append(new Basis(4), first)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.append(new Basis(2), first)).isInstanceOf(IllegalArgumentException.class);
    }
}
