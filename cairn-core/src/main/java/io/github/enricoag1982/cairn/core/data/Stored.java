package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Something the store holds with a history: an entity or a relationship (design D4 of {@code add-entity-versions} and
 * D2 of {@code add-relationships}). It has an identifier and a type, which never change, and an append-only map from
 * basis to the {@link Timeline} that was known after the transaction of that basis.
 *
 * <p>Only the writer appends, always under a basis above every earlier one, and a reader asks for the timeline at or
 * below the basis of its view, so a reader never sees what a commit in progress is adding.
 *
 * @param <I> the kind of identifier
 */
abstract class Stored<I extends Id> {

    private final I id;
    private final TypeId type;
    private final ConcurrentSkipListMap<Long, Timeline> timelines = new ConcurrentSkipListMap<>();

    Stored(I id, TypeId type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    I id() {
        return id;
    }

    TypeId type() {
        return type;
    }

    /** Records the timeline known after the transaction of a basis; the basis must be above all earlier ones. */
    void append(Basis basis, Timeline timeline) {
        Map.Entry<Long, Timeline> last = timelines.lastEntry();
        if (last != null && last.getKey() >= basis.value()) {
            throw new IllegalArgumentException("Basis " + basis.value() + " does not follow " + last.getKey() + ".");
        }
        timelines.put(basis.value(), timeline);
    }

    /** The timeline known after the latest transaction that touched this, or empty if there is none. */
    Timeline latest() {
        Map.Entry<Long, Timeline> last = timelines.lastEntry();
        return last == null ? Timeline.EMPTY : last.getValue();
    }

    /** The basis of the latest transaction that recorded a timeline of this. */
    Basis latestBasis() {
        Map.Entry<Long, Timeline> last = timelines.lastEntry();
        return last == null ? Basis.NONE : new Basis(last.getKey());
    }

    /** The timeline known after the latest transaction at or below a basis; empty if this was not recorded. */
    Timeline at(Basis basis) {
        Map.Entry<Long, Timeline> entry = timelines.floorEntry(basis.value());
        return entry == null ? Timeline.EMPTY : entry.getValue();
    }
}
