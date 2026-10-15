package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An entity or a relationship with every timeline it has had, as a snapshot file holds it: for each transaction that
 * recorded it, the timeline known after that transaction.
 *
 * @param id       the ID of the entity or relationship; the kind of the ID tells which it is
 * @param type     the identifier of its type
 * @param source   the source of a relationship; empty for an entity
 * @param target   the target of a relationship; empty for an entity
 * @param versions the timelines, by the basis of the transaction that recorded them, in the order of the bases
 */
public record History(
        Id id,
        TypeId type,
        Optional<EntityId> source,
        Optional<EntityId> target,
        List<Version> versions) {

    /**
     * The timeline that one transaction left an entity or relationship with.
     *
     * @param basis    the basis of the transaction
     * @param segments the segments of the timeline, in order
     */
    public record Version(Basis basis, List<SegmentData> segments) {

        /**
         * Rejects null components and copies the segments.
         */
        public Version {
            Objects.requireNonNull(basis, "basis");
            segments = List.copyOf(segments);
        }
    }

    /**
     * Rejects null components and copies the versions.
     */
    public History {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        versions = List.copyOf(versions);
    }
}
