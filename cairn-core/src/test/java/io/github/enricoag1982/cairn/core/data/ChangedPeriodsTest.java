package io.github.enricoag1982.cairn.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The periods in which an entity or relationship changed (ADR-0029, design D2 of {@code add-change-feed}): those in which
 * a view at the basis of a transaction and a view at the basis before read different versions of it, absent included. A
 * version is a segment's values and the transaction that recorded it, not its period, so a segment that was only cut is
 * the version it was.
 */
class ChangedPeriodsTest {

    private static final AttributeId BALANCE = new AttributeId(1);
    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB_1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MAR_1 = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant MAR_15 = Instant.parse("2026-03-15T00:00:00Z");
    private static final Instant JUN_1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant OCT_1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JAN_1_2027 = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant RECORDED_1 = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant RECORDED_2 = Instant.parse("2026-03-16T09:00:00Z");
    private static final Instant RECORDED_3 = Instant.parse("2026-04-20T09:00:00Z");

    /** A version of the balance: the value, and the transaction that recorded it, 1 to 3. */
    private static Segment segment(Instant from, Instant end, int balance, int transaction) {
        Instant recordedAt = switch (transaction) {
            case 1 -> RECORDED_1;
            case 2 -> RECORDED_2;
            default -> RECORDED_3;
        };
        return new Segment(from, end, Map.of(BALANCE, Value.of(balance)), recordedAt, new Basis(transaction));
    }

    private static EffectivePeriod from(Instant from) {
        return EffectivePeriod.from(from);
    }

    private static EffectivePeriod between(Instant from, Instant until) {
        return EffectivePeriod.between(from, until);
    }

    @Test
    void anEntityThatIsCreatedChangedFromItsStartForAsLongAsItExists() {
        assertThat(ChangedPeriods.of(List.of(), List.of(segment(JAN_1, Segment.OPEN, 1000, 1))))
                .containsExactly(from(JAN_1));
        assertThat(ChangedPeriods.of(List.of(), List.of(segment(MAR_1, OCT_1, 1000, 1))))
                .containsExactly(between(MAR_1, OCT_1));
    }

    @Test
    void anEntityWithSeveralVersionsThatIsCreatedChangedThroughoutThem() {
        List<Segment> created = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, Segment.OPEN, 1500, 1));

        assertThat(ChangedPeriods.of(List.of(), created)).as("neighboring versions are one period")
                .containsExactly(from(JAN_1));
    }

    @Test
    void aCorrectionChangesTheLaterPeriodOnly() {
        List<Segment> before = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, Segment.OPEN, 1500, 2));
        List<Segment> after = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, Segment.OPEN, 1200, 3));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(MAR_15));
    }

    @Test
    void aWriteInTheMiddleCutsTheVersionAroundItAndChangesTheMiddleOnly() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, FEB_1, 1000, 1), segment(FEB_1, MAR_1, 2000, 2),
                segment(MAR_1, Segment.OPEN, 1000, 1));

        assertThat(ChangedPeriods.of(before, after)).as("the cut version is the version it was")
                .containsExactly(between(FEB_1, MAR_1));
    }

    @Test
    void endingAnEntityChangesItFromTheEndOnwards() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, JUN_1, 1000, 1));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(JUN_1));
    }

    @Test
    void endingAnEntityThatHasALaterVersionChangesFromTheEndOnwards() {
        List<Segment> before = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, Segment.OPEN, 1500, 2));
        List<Segment> after = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, JUN_1, 1500, 2));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(JUN_1));
    }

    @Test
    void endingAnEntityBeforeALaterVersionStartsRemovesTheVersionAndTheStretchBeforeIt() {
        List<Segment> before = List.of(segment(JAN_1, MAR_1, 1000, 1), segment(MAR_1, JUN_1, 1500, 2));
        List<Segment> after = List.of(segment(JAN_1, FEB_1, 1000, 1));

        assertThat(ChangedPeriods.of(before, after)).as("the rest of the first version and the removed one are one "
                + "period").containsExactly(between(FEB_1, JUN_1));
    }

    @Test
    void aSegmentThatRejoinsADifferentOneIsNotMergedIntoItsNeighbor() {
        List<Segment> before = List.of(segment(JAN_1, FEB_1, 1000, 1), segment(FEB_1, MAR_1, 2000, 1),
                segment(MAR_1, Segment.OPEN, 3000, 1));
        List<Segment> after = List.of(segment(JAN_1, FEB_1, 1111, 2), segment(FEB_1, MAR_1, 2000, 1),
                segment(MAR_1, Segment.OPEN, 3333, 2));

        assertThat(ChangedPeriods.of(before, after)).as("the middle version stays out")
                .containsExactly(between(JAN_1, FEB_1), from(MAR_1));
    }

    @Test
    void neighboringChangedStretchesAreOnePeriod() {
        List<Segment> before = List.of(segment(JAN_1, MAR_1, 1000, 1), segment(MAR_1, Segment.OPEN, 2000, 1));
        List<Segment> after = List.of(segment(JAN_1, MAR_1, 1111, 2), segment(MAR_1, Segment.OPEN, 2222, 2));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(JAN_1));
    }

    @Test
    void theSameValuesRecordedAgainByAnotherTransactionAreAnotherVersion() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, Segment.OPEN, 1000, 2));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(JAN_1));
    }

    @Test
    void theSameValuesRecordedByTheSameTransactionAtAnotherTimeAreAnotherVersionToo() {
        Segment earlier = new Segment(JAN_1, Segment.OPEN, Map.of(BALANCE, Value.of(1000)), RECORDED_1, new Basis(1));
        Segment later = new Segment(JAN_1, Segment.OPEN, Map.of(BALANCE, Value.of(1000)), RECORDED_2, new Basis(1));

        assertThat(ChangedPeriods.of(List.of(earlier), List.of(later))).containsExactly(from(JAN_1));
    }

    @Test
    void aVersionThatWasOnlySplitIsTheVersionItWas() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, FEB_1, 1000, 1), segment(FEB_1, Segment.OPEN, 1000, 1));

        assertThat(ChangedPeriods.of(before, after)).isEmpty();
    }

    @Test
    void timelinesThatAreEqualOrEmptyDoNotDiffer() {
        List<Segment> same = List.of(segment(JAN_1, MAR_15, 1000, 1), segment(MAR_15, Segment.OPEN, 1500, 2));

        assertThat(ChangedPeriods.of(same, same)).isEmpty();
        assertThat(ChangedPeriods.of(List.of(), List.of())).isEmpty();
    }

    @Test
    void aFutureDatedWriteChangesFromItsDateOnward() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, JAN_1_2027, 1000, 1), segment(JAN_1_2027, Segment.OPEN, 2000, 2));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(from(JAN_1_2027));
    }

    @Test
    void aFutureDatedWriteThatEndsInAnotherChangesTheStretchBetween() {
        List<Segment> before = List.of(segment(JAN_1, Segment.OPEN, 1000, 1));
        List<Segment> after = List.of(segment(JAN_1, JAN_1_2027, 1000, 1),
                segment(JAN_1_2027, Instant.parse("2027-06-01T00:00:00Z"), 2000, 2),
                segment(Instant.parse("2027-06-01T00:00:00Z"), Segment.OPEN, 1000, 1));

        assertThat(ChangedPeriods.of(before, after)).containsExactly(
                between(JAN_1_2027, Instant.parse("2027-06-01T00:00:00Z")));
    }

    @Test
    void theTimelinesOfARelationshipThatIsMovedAreComparedLikeAnyOther() {
        // The old HeldBy ends on 1 June, and nothing else about it changes.
        Segment held = new Segment(JAN_1, Segment.OPEN, Map.of(), RECORDED_1, new Basis(1));
        Segment heldUntilJune = new Segment(JAN_1, JUN_1, Map.of(), RECORDED_1, new Basis(1));

        assertThat(ChangedPeriods.of(List.of(held), List.of(heldUntilJune))).containsExactly(from(JUN_1));
    }

    @Test
    void aTimelineWithAGapIsComparedStretchByStretch() {
        List<Segment> before = List.of(segment(JAN_1, FEB_1, 1000, 1), segment(MAR_1, JUN_1, 2000, 1));
        List<Segment> after = List.of(segment(JAN_1, FEB_1, 1000, 1), segment(FEB_1, JUN_1, 2000, 2));

        assertThat(ChangedPeriods.of(before, after)).as("the gap is a stretch in which the entity did not exist")
                .containsExactly(between(FEB_1, JUN_1));
    }
}
