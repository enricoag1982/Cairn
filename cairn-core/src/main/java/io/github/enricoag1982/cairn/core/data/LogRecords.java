package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.storage.DataRecord;
import io.github.enricoag1982.cairn.core.storage.Effect;
import io.github.enricoag1982.cairn.core.storage.SegmentData;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import java.util.List;
import java.util.Optional;

/**
 * Converts between what the store holds and the records of the commit log: the timelines of {@link Preparation.Change}s
 * and {@link Segment}s on one side, the {@link Effect}s and {@link SegmentData} of {@code core.storage} on the other.
 * Nothing is validated or computed; the conversion only changes the shape.
 */
final class LogRecords {

    private LogRecords() {
    }

    /** The record of a commit: the transaction as submitted, its receipt, and the changes it made. */
    static DataRecord dataRecord(Transaction transaction, Receipt receipt, List<Preparation.Change> changes) {
        return new DataRecord(transaction, receipt, changes.stream().map(LogRecords::effect).toList());
    }

    private static Effect effect(Preparation.Change change) {
        return new Effect(change.id(), change.type(), change.created(), Optional.ofNullable(change.source()),
                Optional.ofNullable(change.target()), segments(change.timeline()));
    }

    static List<SegmentData> segments(Timeline timeline) {
        return timeline.segments().stream()
                .map(segment -> new SegmentData(segment.from(), segment.end(), segment.values(), segment.recordedAt(),
                        segment.recordedBasis()))
                .toList();
    }

    static Timeline timeline(List<SegmentData> segments) {
        return Timeline.restored(segments.stream()
                .map(data -> new Segment(data.from(), data.end(), data.values(), data.recordedAt(),
                        data.recordedBasis()))
                .toList());
    }
}
