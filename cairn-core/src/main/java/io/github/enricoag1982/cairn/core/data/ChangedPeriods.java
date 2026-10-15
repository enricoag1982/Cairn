package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The effective periods in which an entity or relationship changed between two timelines: those in which a view at the
 * basis of a transaction and a view at the basis before read different versions of it (ADR-0029, design D2 of
 * {@code add-change-feed}), which the {@code CHANGE} keys of the {@link Ledger} record.
 *
 * <p>The boundaries of both timelines cut effective time into stretches in each of which each timeline has one segment
 * or none. A stretch is changed if one timeline has a segment there and the other none, or both have one and the segments
 * are not the same version. Two segments are the same version if they have the same values and were recorded by the same
 * transaction, which is its transaction time and its basis, whatever their periods are: a segment that was only cut, by an
 * end-date or by a write in another stretch, is still the version it was. A write that leaves a period's values as they
 * were records no new version there, so that period is not changed. Versions are compared by their values and the
 * transaction time and basis that recorded them, not by their bounds (ADR-0029). Neighboring changed stretches are
 * merged.
 */
final class ChangedPeriods {

    private ChangedPeriods() {
    }

    /**
     * Finds the periods in which two timelines differ.
     *
     * @param before the segments of the timeline before the transaction, in order; none for an entity that is created
     * @param after  the segments of the timeline after it, in order
     * @return the periods, in order, neither overlapping nor touching; the last one is open if the timelines differ
     *         forever
     */
    static List<EffectivePeriod> of(List<Segment> before, List<Segment> after) {
        NavigableSet<Instant> bounds = new TreeSet<>();
        for (Segment segment : before) {
            bounds.add(segment.from());
            bounds.add(segment.end());
        }
        for (Segment segment : after) {
            bounds.add(segment.from());
            bounds.add(segment.end());
        }
        List<EffectivePeriod> periods = new ArrayList<>();
        Instant changedFrom = null;
        int mine = 0;
        int theirs = 0;
        Instant previous = null;
        for (Instant bound : bounds) {
            if (previous != null) {
                // The segments are in order, so the one that holds the instant is at or after the last one found.
                while (mine < before.size() && !before.get(mine).end().isAfter(previous)) {
                    mine++;
                }
                while (theirs < after.size() && !after.get(theirs).end().isAfter(previous)) {
                    theirs++;
                }
                Segment was = mine < before.size() && before.get(mine).contains(previous) ? before.get(mine) : null;
                Segment is = theirs < after.size() && after.get(theirs).contains(previous) ? after.get(theirs) : null;
                if (differ(was, is)) {
                    if (changedFrom == null) {
                        changedFrom = previous;
                    }
                } else if (changedFrom != null) {
                    periods.add(period(changedFrom, previous));
                    changedFrom = null;
                }
            }
            previous = bound;
        }
        if (changedFrom != null) {
            periods.add(period(changedFrom, previous));
        }
        return periods;
    }

    /** Tells whether two segments that hold the same instant are different versions, an absent one counting as a version. */
    private static boolean differ(Segment was, Segment is) {
        if (was == null || is == null) {
            return was != is;
        }
        return !was.values().equals(is.values()) || !was.sameRecording(is);
    }

    private static EffectivePeriod period(Instant from, Instant end) {
        return new EffectivePeriod(from, end.equals(Segment.OPEN) ? Optional.empty() : Optional.of(end));
    }
}
