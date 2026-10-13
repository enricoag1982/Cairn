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

/**
 * Design D4 and D5: timelines split at the bounds of a change, and merge adjacent segments only when they have equal
 * values and the same recording.
 */
class TimelineTest {

    private static final AttributeId NAME = new AttributeId(1);
    private static final AttributeId BALANCE = new AttributeId(2);
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SEP = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant RECORDED_1 = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant RECORDED_2 = Instant.parse("2026-03-16T09:00:00Z");
    private static final Instant RECORDED_3 = Instant.parse("2026-04-20T09:00:00Z");

    private static Map<AttributeId, Value> values(String name, int balance) {
        return Map.of(NAME, Value.of(name), BALANCE, Value.of(balance));
    }

    private static Timeline acme() {
        return Timeline.created(JAN, Segment.OPEN, values("ACME", 1000), RECORDED_1, new Basis(1));
    }

    /** Sets an attribute, recorded by the second transaction. */
    private static Timeline set(Timeline timeline, Instant from, Instant end, AttributeId attribute, Value value) {
        return set(timeline, from, end, attribute, value, RECORDED_2, new Basis(2));
    }

    private static Timeline set(Timeline timeline, Instant from, Instant end, AttributeId attribute, Value value,
            Instant recordedAt, Basis recordedBasis) {
        return timeline.update(from, end, old -> {
            Map<AttributeId, Value> changed = new HashMap<>(old);
            changed.put(attribute, value);
            return changed;
        }, recordedAt, recordedBasis);
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
    void aClosedChangeSplitsTheSegmentsThatContainItsBounds() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));
        Instant february = Instant.parse("2026-02-01T00:00:00Z");
        Instant july = Instant.parse("2026-07-01T00:00:00Z");

        Timeline timeline = set(split, february, july, NAME, Value.of("ACME Holdings"));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, february, MAR, JUN, july);
        assertThat(timeline.segments()).extracting(Segment::end).containsExactly(february, MAR, JUN, july,
                Segment.OPEN);
        assertThat(timeline.segments()).extracting(segment -> segment.values().get(NAME))
                .containsExactly(Value.of("ACME"), Value.of("ACME Holdings"), Value.of("ACME Holdings"),
                        Value.of("ACME Holdings"), Value.of("ACME"));
    }

    @Test
    void aChangeThatStartsAndEndsAtTheBoundsOfSegmentsSplitsNothing() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        Timeline timeline = set(split, MAR, JUN, BALANCE, Value.of(1600));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN);
        assertThat(timeline.segments()).extracting(Segment::end).containsExactly(MAR, JUN, Segment.OPEN);
        assertThat(timeline.segments().get(1).values()).isEqualTo(values("ACME", 1600));
    }

    @Test
    void anOpenChangeChangesEveryLaterSegment() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        Timeline timeline = set(split, MAR, Segment.OPEN, NAME, Value.of("ACME Holdings"));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN);
        assertThat(timeline.segments()).extracting(Segment::end).containsExactly(MAR, JUN, Segment.OPEN);
        assertThat(timeline.segments().get(0).values()).isEqualTo(values("ACME", 1000));
        assertThat(timeline.segments().get(1).values()).isEqualTo(values("ACME Holdings", 1500));
        assertThat(timeline.segments().get(2).values()).isEqualTo(values("ACME Holdings", 1000));
    }

    @Test
    void anOpenChangeNeverExtendsAClosedEntity() {
        Timeline closed = Timeline.created(JAN, JUN, values("ACME", 1000), RECORDED_1, new Basis(1));

        Timeline timeline = set(closed, MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.segments()).extracting(Segment::from).containsExactly(JAN, MAR);
        assertThat(timeline.segments()).extracting(Segment::end).containsExactly(MAR, JUN);
        assertThat(timeline.lifetime()).contains(new Timeline.Lifetime(JAN, JUN));
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
    void adjacentSegmentsWithEqualValuesAndTheSameRecordingAreMerged() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        Timeline together = set(split, JAN, MAR, BALANCE, Value.of(1500));

        assertThat(together.segments()).hasSize(1);
        assertThat(together.segments().getFirst().from()).isEqualTo(JAN);
        assertThat(together.segments().getFirst().end()).isEqualTo(Segment.OPEN);
        assertThat(together.segments().getFirst().recordedBasis()).isEqualTo(new Basis(2));
        assertThat(together.segments().getFirst().recordedAt()).isEqualTo(RECORDED_2);
    }

    @Test
    void adjacentSegmentsWithEqualValuesThatDifferentTransactionsRecordedAreNotMerged() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        Timeline back = set(split, MAR, Segment.OPEN, BALANCE, Value.of(1000), RECORDED_3, new Basis(3));

        assertThat(back.segments()).extracting(Segment::from).containsExactly(JAN, MAR);
        assertThat(back.segments()).extracting(Segment::end).containsExactly(MAR, Segment.OPEN);
        assertThat(back.segments()).extracting(Segment::values).containsExactly(values("ACME", 1000),
                values("ACME", 1000));
        assertThat(back.segments()).extracting(Segment::recordedBasis).containsExactly(new Basis(1), new Basis(3));
        assertThat(back.segments()).extracting(Segment::recordedAt).containsExactly(RECORDED_1, RECORDED_3);
    }

    @Test
    void settingAValueBackInTheMiddleKeepsThreeSegmentsEachWithItsRecording() {
        Timeline split = set(acme(), MAR, JUN, BALANCE, Value.of(1500));
        assertThat(split.segments()).hasSize(3);

        Timeline back = set(split, MAR, JUN, BALANCE, Value.of(1000));

        assertThat(back.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN);
        assertThat(back.segments()).extracting(Segment::values).containsOnly(values("ACME", 1000));
        assertThat(back.segments()).extracting(Segment::recordedBasis).containsExactly(new Basis(1), new Basis(2),
                new Basis(1));
        assertThat(back.sameState(acme())).isTrue();
    }

    @Test
    void clearingAValueDoesNotMergeWithTheNeighbourThatNeverHadItAndWasRecordedEarlier() {
        Timeline named = Timeline.created(JAN, Segment.OPEN, Map.of(NAME, Value.of("ACME")), RECORDED_1, new Basis(1));
        Timeline withBalance = set(named, MAR, Segment.OPEN, BALANCE, Value.of(1500));
        assertThat(withBalance.segments()).hasSize(2);

        Timeline cleared = withBalance.update(MAR, Segment.OPEN, old -> {
            Map<AttributeId, Value> changed = new HashMap<>(old);
            changed.remove(BALANCE);
            return changed;
        }, RECORDED_3, new Basis(3));

        assertThat(cleared.segments()).hasSize(2);
        assertThat(cleared.segments()).extracting(Segment::values).containsOnly(Map.of(NAME, Value.of("ACME")));
        assertThat(cleared.segments()).extracting(Segment::recordedBasis).containsExactly(new Basis(1), new Basis(3));
        assertThat(cleared.sameState(named)).isTrue();
    }

    @Test
    void clearingAValueInTheMiddleAndSettingItBackKeepsThreeSegments() {
        Timeline cleared = acme().update(MAR, JUN, old -> Map.of(NAME, Value.of("ACME")), RECORDED_2, new Basis(2));
        assertThat(cleared.segments()).hasSize(3);
        assertThat(cleared.segments().get(1).values()).isEqualTo(Map.of(NAME, Value.of("ACME")));

        Timeline back = set(cleared, MAR, JUN, BALANCE, Value.of(1000));

        assertThat(back.segments()).hasSize(3);
        assertThat(back.segments().get(1).values()).isEqualTo(values("ACME", 1000));
        assertThat(back.segments().get(1).recordedBasis()).isEqualTo(new Basis(2));
        assertThat(back.sameState(acme())).isTrue();
    }

    @Test
    void aClearedValueThatIsSetToAnotherValueIsNotMerged() {
        Timeline cleared = acme().update(MAR, JUN, old -> Map.of(NAME, Value.of("ACME")), RECORDED_2, new Basis(2));

        Timeline other = set(cleared, MAR, JUN, BALANCE, Value.of(2000));

        assertThat(other.segments()).extracting(Segment::from).containsExactly(JAN, MAR, JUN);
    }

    @Test
    void neighbouringChangesToTheSameValueMergeIntoOneSegment() {
        Timeline first = set(acme(), MAR, JUN, BALANCE, Value.of(1500));

        Timeline both = set(first, JUN, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(both.segments()).extracting(Segment::from).containsExactly(JAN, MAR);
        assertThat(both.segments()).extracting(Segment::end).containsExactly(MAR, Segment.OPEN);
        assertThat(both.segments().get(1).values()).isEqualTo(values("ACME", 1500));
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
    void timelinesThatDifferentRecordingsSplitAreTheSameStateAsOneThatWasNeverSplit() {
        Timeline split = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));
        Timeline restored = set(split, MAR, Segment.OPEN, BALANCE, Value.of(1000), RECORDED_3, new Basis(3));
        assertThat(restored.segments()).hasSize(2);

        assertThat(restored.sameState(acme())).isTrue();
        assertThat(acme().sameState(restored)).isTrue();
        assertThat(restored).isNotEqualTo(acme());
        assertThat(restored.sameState(split)).isFalse();
    }

    @Test
    void timelinesWithOtherLifetimesAreNotTheSameState() {
        Timeline earlier = Timeline.of(List.of(
                new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1)),
                new Segment(MAR, Segment.OPEN, values("ACME", 1000), RECORDED_2, new Basis(2))));
        Timeline closed = Timeline.created(JAN, JUN, values("ACME", 1000), RECORDED_1, new Basis(1));

        assertThat(earlier.sameState(closed)).isFalse();
        assertThat(closed.sameState(earlier)).isFalse();
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
    void buildingFromSegmentsSortsThemAndMergesNeighboursWithEqualValuesAndTheSameRecording() {
        Segment late = new Segment(MAR, Segment.OPEN, values("ACME", 1000), RECORDED_1, new Basis(1));
        Segment early = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));

        Timeline timeline = Timeline.of(List.of(late, early));

        assertThat(timeline.segments()).hasSize(1);
        assertThat(timeline.segments().getFirst().from()).isEqualTo(JAN);
        assertThat(timeline.segments().getFirst().end()).isEqualTo(Segment.OPEN);
        assertThat(timeline.segments().getFirst().recordedBasis()).isEqualTo(new Basis(1));
        assertThat(timeline.segments().getFirst().recordedAt()).isEqualTo(RECORDED_1);
    }

    @Test
    void ofTwoSegmentsWithEqualValuesThatDifferentTransactionsRecordedNoneIsMerged() {
        Segment first = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_2, new Basis(2));
        Segment second = new Segment(MAR, Segment.OPEN, values("ACME", 1000), RECORDED_1, new Basis(1));

        Timeline timeline = Timeline.of(List.of(first, second));

        assertThat(timeline.segments()).containsExactly(first, second);
    }

    @Test
    void segmentsOfOneBasisButOtherTransactionTimesAreNotMerged() {
        Segment first = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));
        Segment second = new Segment(MAR, Segment.OPEN, values("ACME", 1000), RECORDED_2, new Basis(1));

        assertThat(Timeline.of(List.of(first, second)).segments()).hasSize(2);
    }

    @Test
    void segmentsWithOtherValuesAreNotMergedWhateverTheirRecording() {
        Segment first = new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1));
        Segment second = new Segment(MAR, Segment.OPEN, values("ACME", 1001), RECORDED_1, new Basis(1));

        assertThat(Timeline.of(List.of(first, second)).segments()).hasSize(2);
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

    // ------------------------------------------------------------------ changed segments (add-entity-rules, D3)

    @Test
    void everySegmentOfANewEntityIsChanged() {
        Timeline timeline = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.changedSince(Timeline.EMPTY)).isEqualTo(timeline.segments());
    }

    @Test
    void aTimelineThatIsTheSameHasNoChangedSegments() {
        Timeline timeline = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(timeline.changedSince(timeline)).isEmpty();
        assertThat(Timeline.EMPTY.changedSince(timeline)).isEmpty();
    }

    @Test
    void onlyTheSegmentWithOtherValuesIsChanged() {
        Timeline before = acme();
        Timeline after = set(before, MAR, JUN, BALANCE, Value.of(1500));

        assertThat(after.changedSince(before)).singleElement().satisfies(segment -> {
            assertThat(segment.from()).isEqualTo(MAR);
            assertThat(segment.end()).isEqualTo(JUN);
        });
    }

    @Test
    void aChangeThatLeavesTheValuesAsTheyAreChangesNothing() {
        Timeline before = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));
        Timeline after = set(before, JUN, SEP, BALANCE, Value.of(1500), RECORDED_3, new Basis(3));

        assertThat(after.changedSince(before)).isEmpty();
    }

    @Test
    void aChangeBackToTheOldValuesCountsAsNoChangeEvenIfItSplitsTheTimeline() {
        Timeline before = acme();
        Timeline changedAndBack = set(set(before, MAR, JUN, BALANCE, Value.of(1500), RECORDED_3, new Basis(3)),
                MAR, JUN, BALANCE, Value.of(1000), RECORDED_3, new Basis(3));

        assertThat(changedAndBack.segments()).as("split by the recording").hasSizeGreaterThan(1);
        assertThat(changedAndBack.changedSince(before)).isEmpty();
    }

    @Test
    void cuttingAnEntityOffChangesNoSegment() {
        Timeline before = set(acme(), MAR, Segment.OPEN, BALANCE, Value.of(1500));

        assertThat(before.endAt(SEP).changedSince(before)).isEmpty();
        assertThat(before.endAt(JUN).changedSince(before)).isEmpty();
    }

    @Test
    void aSegmentIsChangedWhereThePreviousTimelineHadAGapOrOtherValuesInPartOfIt() {
        Timeline before = Timeline.of(List.of(
                new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1)),
                new Segment(JUN, Segment.OPEN, values("ACME", 1000), RECORDED_1, new Basis(1))));
        Timeline filled = Timeline.of(List.of(
                new Segment(JAN, Segment.OPEN, values("ACME", 1000), RECORDED_2, new Basis(2))));
        Timeline partlyOther = Timeline.of(List.of(
                new Segment(JAN, MAR, values("ACME", 1000), RECORDED_1, new Basis(1)),
                new Segment(MAR, Segment.OPEN, values("ACME", 7), RECORDED_1, new Basis(1))));

        assertThat(filled.changedSince(before)).as("the gap in the middle").hasSize(1);
        assertThat(filled.changedSince(partlyOther)).as("a part with other values").hasSize(1);
        assertThat(before.changedSince(filled)).isEmpty();
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
