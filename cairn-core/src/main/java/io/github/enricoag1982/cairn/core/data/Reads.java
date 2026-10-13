package io.github.enricoag1982.cairn.core.data;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The reads that one run of a rule makes (design D1 of {@code add-rule-rechecks}): each {@link ReadKey} it read, at the
 * effective times it read it, or across effective time. The views of a rule's context record into it while the rule
 * runs; once the run is over the recorder is closed, and a view that the rule kept records nothing more.
 *
 * <p>Once the recorder is closed nothing in it changes, so the index copies the map of keys and shares the {@link Times}.
 * Used in the thread that holds the store's writer lock, by one run of one rule.
 */
final class Reads {

    /** When one key was read: at some effective instants, or across effective time. */
    static final class Times {

        private boolean acrossTime;
        private NavigableSet<Instant> instants;

        /** Tells whether the key was read across effective time, which any change of the key reaches. */
        boolean acrossTime() {
            return acrossTime;
        }

        /** The effective instants at which the key was read, in order; none if it was read across effective time. */
        NavigableSet<Instant> instants() {
            return instants == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(instants);
        }

        private void add(Instant instant) {
            if (acrossTime) {
                return;
            }
            if (instants == null) {
                instants = new TreeSet<>();
            }
            instants.add(instant);
        }

        private void addAcrossTime() {
            acrossTime = true;
            instants = null;
        }

        /**
         * Tells whether a change in some stretches of effective time reaches this read: a read across effective time
         * is reached by any change, a read at an instant by a change whose stretch contains the instant.
         */
        boolean hitBy(List<Timeline.Stretch> stretches) {
            if (acrossTime) {
                return !stretches.isEmpty();
            }
            for (Timeline.Stretch stretch : stretches) {
                Instant read = instants.ceiling(stretch.from());
                if (read != null && read.isBefore(stretch.end())) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<ReadKey, Times> times = new HashMap<>();
    private boolean closed;

    /** Records a read of a key at an effective instant. */
    void at(ReadKey key, Instant effectiveAt) {
        if (!closed) {
            times.computeIfAbsent(key, k -> new Times()).add(effectiveAt);
        }
    }

    /** Records a read of a key across effective time. */
    void acrossTime(ReadKey key) {
        if (!closed) {
            times.computeIfAbsent(key, k -> new Times()).addAcrossTime();
        }
    }

    /** Ends the run: later reads, by a view that the rule kept, are not recorded. */
    void close() {
        closed = true;
    }

    boolean isEmpty() {
        return times.isEmpty();
    }

    /** What was read, and when; the map is the recorder's own, and the caller must not change it. */
    Map<ReadKey, Times> times() {
        return times;
    }
}
