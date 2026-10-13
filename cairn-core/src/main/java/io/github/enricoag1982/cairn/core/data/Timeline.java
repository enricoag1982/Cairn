package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The complete effective history of an entity as known after one transaction (design D4): an immutable, sorted list of
 * non-overlapping {@link Segment}s. Adjacent segments are merged when they have equal values and the same recording
 * (the same transaction time and basis), so that a timeline lists real changes only and every segment names the one
 * transaction that recorded it. Segments with equal values that different transactions recorded stay apart. Every
 * operation returns a new timeline.
 *
 * <p>Nothing in the engine creates a gap in a timeline: an entity is created for one period, writes never extend its
 * lifetime, and an end-date cuts the end. The operations nevertheless make no use of that, except {@link #lifetime()}.
 */
final class Timeline {

    /** The history of an entity that was not recorded yet. */
    static final Timeline EMPTY = new Timeline(List.of());

    /** A stretch of effective time in which one attribute has one value. */
    private record Span(Instant from, Instant end, Value value) {
    }

    /** A stretch of effective time in which the entity has the same values. */
    private record Run(Instant from, Instant end, Map<AttributeId, Value> values) {
    }

    private final List<Segment> segments;

    private Timeline(List<Segment> segments) {
        this.segments = segments;
    }

    /**
     * Builds a timeline from segments that do not overlap: sorts them and merges adjacent segments that have equal
     * values and the same recording. Segments that different transactions recorded are never merged, even if their
     * values are equal, so the merged segment keeps the recording that both had.
     *
     * @param segments the segments, in any order
     * @return the timeline
     * @throws IllegalArgumentException if two segments overlap
     */
    static Timeline of(List<Segment> segments) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparing(Segment::from));
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : sorted) {
            if (merged.isEmpty()) {
                merged.add(segment);
                continue;
            }
            Segment last = merged.getLast();
            if (segment.from().isBefore(last.end())) {
                throw new IllegalArgumentException("Segments overlap: " + last.period() + " and " + segment.period());
            }
            if (segment.from().equals(last.end()) && segment.values().equals(last.values())
                    && segment.sameRecording(last)) {
                merged.set(merged.size() - 1, last.withBounds(last.from(), segment.end()));
            } else {
                merged.add(segment);
            }
        }
        return new Timeline(List.copyOf(merged));
    }

    /**
     * The timeline that recovery reads back: the segments of a timeline that an earlier commit built, in order. They are
     * taken as they are, without merging or checking, because they are what the commit recorded.
     */
    static Timeline restored(List<Segment> segments) {
        return new Timeline(List.copyOf(segments));
    }

    /** A timeline of one segment: an entity that is created. */
    static Timeline created(Instant from, Instant end, Map<AttributeId, Value> values, Instant recordedAt,
            Basis recordedBasis) {
        return new Timeline(List.of(new Segment(from, end, values, recordedAt, recordedBasis)));
    }

    List<Segment> segments() {
        return segments;
    }

    boolean isEmpty() {
        return segments.isEmpty();
    }

    /** The segment that contains an instant, if the entity exists then. */
    Optional<Segment> at(Instant instant) {
        for (Segment segment : segments) {
            if (segment.contains(instant)) {
                return Optional.of(segment);
            }
            if (segment.from().isAfter(instant)) {
                break;
            }
        }
        return Optional.empty();
    }

    /** The start of the first segment and the end of the last, or empty for an empty timeline. */
    Optional<Lifetime> lifetime() {
        return segments.isEmpty() ? Optional.empty()
                : Optional.of(new Lifetime(segments.getFirst().from(), segments.getLast().end()));
    }

    /** From when until when an entity exists; the end is {@link Segment#OPEN} for an entity without one. */
    record Lifetime(Instant from, Instant end) {
    }

    /**
     * Tells whether the entity exists at every instant of a period.
     *
     * @param from the first instant
     * @param end  the first instant after the period, or {@link Segment#OPEN}
     * @return {@code true} if segments cover the whole period without a gap
     */
    boolean covers(Instant from, Instant end) {
        Instant cursor = from;
        for (Segment segment : segments) {
            if (!segment.end().isAfter(cursor)) {
                continue;
            }
            if (segment.from().isAfter(cursor)) {
                return false;
            }
            cursor = segment.end();
            if (!cursor.isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    /** A stretch of effective time, from an instant to the first instant after it, which may be {@link Segment#OPEN}. */
    record Stretch(Instant from, Instant end) {
    }

    /**
     * The stretches of effective time in which this timeline exists and another one does not, merged where they touch.
     * An entity, a relationship and its ends are compared this way: a relationship lies within the lifetimes of its
     * ends if nothing is left over.
     *
     * @param other the timeline to subtract
     * @return the stretches, in order; empty if the other timeline exists wherever this one does
     */
    List<Stretch> uncoveredBy(Timeline other) {
        List<Stretch> missing = new ArrayList<>();
        for (Segment mine : segments) {
            Instant cursor = mine.from();
            for (Segment theirs : other.segments) {
                if (!theirs.end().isAfter(cursor)) {
                    continue;
                }
                if (!theirs.from().isBefore(mine.end())) {
                    break;
                }
                if (theirs.from().isAfter(cursor)) {
                    add(missing, cursor, theirs.from());
                }
                cursor = max(cursor, theirs.end());
                if (!cursor.isBefore(mine.end())) {
                    break;
                }
            }
            if (cursor.isBefore(mine.end())) {
                add(missing, cursor, mine.end());
            }
        }
        return missing;
    }

    /** Appends a stretch, merging it into the last one if they touch. */
    private static void add(List<Stretch> stretches, Instant from, Instant end) {
        if (!stretches.isEmpty() && stretches.getLast().end().equals(from)) {
            Stretch last = stretches.removeLast();
            stretches.add(new Stretch(last.from(), end));
        } else {
            stretches.add(new Stretch(from, end));
        }
    }

    /** Tells whether this timeline exists at some instant at which a previous one did not: it was created or grew. */
    boolean grewSince(Timeline previous) {
        return !uncoveredBy(previous).isEmpty();
    }

    /** Tells whether a previous timeline existed at some instant at which this one does not: it was cut off. */
    boolean shrankSince(Timeline previous) {
        return !previous.uncoveredBy(this).isEmpty();
    }

    /**
     * The first stretch of effective time in which both this timeline and another exist.
     *
     * @param other the other timeline
     * @return the earliest common stretch; empty if the lifetimes have no instant in common
     */
    Optional<Stretch> firstOverlap(Timeline other) {
        Stretch first = null;
        for (Segment mine : segments) {
            for (Segment theirs : other.segments) {
                Instant from = max(mine.from(), theirs.from());
                Instant end = min(mine.end(), theirs.end());
                if (from.isBefore(end) && (first == null || from.isBefore(first.from()))) {
                    first = new Stretch(from, end);
                }
            }
        }
        return Optional.ofNullable(first);
    }

    /**
     * Changes the values of the entity in a period. The segments are split at the bounds of the period, and the change
     * is applied to the values of each part inside it. A part whose values the change leaves as they are keeps its
     * recording; a part that changes is recorded by the given transaction. Adjacent segments with equal values and the
     * same recording are merged.
     *
     * @param from          the first instant of the period
     * @param end           the first instant after the period, or {@link Segment#OPEN}
     * @param change        computes the new values from the old ones
     * @param recordedAt    the transaction time of the transaction that makes the change
     * @param recordedBasis the basis of that transaction
     * @return the changed timeline
     */
    Timeline update(Instant from, Instant end, UnaryOperator<Map<AttributeId, Value>> change, Instant recordedAt,
            Basis recordedBasis) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (!segment.end().isAfter(from) || !segment.from().isBefore(end)) {
                result.add(segment);
                continue;
            }
            if (segment.from().isBefore(from)) {
                result.add(segment.withBounds(segment.from(), from));
            }
            Instant partFrom = max(segment.from(), from);
            Instant partEnd = min(segment.end(), end);
            Map<AttributeId, Value> changed = change.apply(segment.values());
            result.add(changed.equals(segment.values())
                    ? segment.withBounds(partFrom, partEnd)
                    : new Segment(partFrom, partEnd, changed, recordedAt, recordedBasis));
            if (segment.end().isAfter(end)) {
                result.add(segment.withBounds(end, segment.end()));
            }
        }
        return of(result);
    }

    /**
     * Cuts the entity off at an instant: every segment ends there at the latest, and the ones that start there or later
     * are dropped.
     *
     * @param instant the first instant at which the entity no longer exists
     * @return the shortened timeline
     */
    Timeline endAt(Instant instant) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (!segment.from().isBefore(instant)) {
                continue;
            }
            result.add(segment.end().isAfter(instant) ? segment.withBounds(segment.from(), instant) : segment);
        }
        return of(result);
    }

    /**
     * Tells whether two timelines give the entity the same values at every instant, whoever recorded them. Adjacent
     * segments with equal values count as one stretch here, so a timeline that was split by two recordings equals the
     * one that was never split.
     */
    boolean sameState(Timeline other) {
        return runs().equals(other.runs());
    }

    /** The stretches in which the entity has the same values, with adjacent equal ones merged. */
    private List<Run> runs() {
        List<Run> runs = new ArrayList<>();
        for (Segment segment : segments) {
            if (!runs.isEmpty() && runs.getLast().end().equals(segment.from())
                    && runs.getLast().values().equals(segment.values())) {
                Run last = runs.removeLast();
                runs.add(new Run(last.from(), segment.end(), last.values()));
            } else {
                runs.add(new Run(segment.from(), segment.end(), segment.values()));
            }
        }
        return runs;
    }

    /**
     * The segments of this timeline in which the entity has other values than in a previous timeline at some instant,
     * including an instant at which it did not exist before. A segment that only has a different period, because the
     * entity was cut off or because an equal change split it, is not changed.
     *
     * @param previous the timeline to compare with
     * @return the changed segments, in order
     */
    List<Segment> changedSince(Timeline previous) {
        return segments.stream().filter(segment -> !previous.hasValuesThroughout(segment)).toList();
    }

    /** Tells whether the entity has the values of a segment at every instant of the segment's period. */
    private boolean hasValuesThroughout(Segment wanted) {
        Instant cursor = wanted.from();
        for (Segment segment : segments) {
            if (!segment.end().isAfter(cursor)) {
                continue;
            }
            if (segment.from().isAfter(cursor) || !segment.values().equals(wanted.values())) {
                return false;
            }
            cursor = segment.end();
            if (!cursor.isBefore(wanted.end())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether two timelines give an attribute the same value at every instant. An entity that does not exist, and
     * an entity without a value for the attribute, are the same here.
     */
    boolean sameValues(Timeline other, AttributeId attribute) {
        return spans(attribute).equals(other.spans(attribute));
    }

    /** The stretches in which an attribute has a value, with adjacent equal ones merged. */
    private List<Span> spans(AttributeId attribute) {
        List<Span> spans = new ArrayList<>();
        for (Segment segment : segments) {
            Value value = segment.values().get(attribute);
            if (value == null) {
                continue;
            }
            if (!spans.isEmpty() && spans.getLast().end().equals(segment.from())
                    && spans.getLast().value().equals(value)) {
                Span last = spans.removeLast();
                spans.add(new Span(last.from(), segment.end(), value));
            } else {
                spans.add(new Span(segment.from(), segment.end(), value));
            }
        }
        return spans;
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Timeline timeline && segments.equals(timeline.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

    @Override
    public String toString() {
        return "Timeline" + segments;
    }
}
