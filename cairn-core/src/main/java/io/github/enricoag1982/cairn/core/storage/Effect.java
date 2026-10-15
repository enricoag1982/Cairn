package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a data transaction did to one entity or relationship: the timeline it has after the transaction, which recovery
 * writes to the ledger under the transaction's basis.
 *
 * @param id       the ID of the entity or relationship; the kind of the ID tells which it is
 * @param type     the identifier of its type, which never changes
 * @param created  whether the transaction created it
 * @param source   the source of a relationship; empty for an entity
 * @param target   the target of a relationship; empty for an entity
 * @param segments the segments of the timeline after the transaction, in order
 */
public record Effect(
        Id id,
        TypeId type,
        boolean created,
        Optional<EntityId> source,
        Optional<EntityId> target,
        List<SegmentData> segments) {

    /**
     * Rejects null components and copies the segments.
     */
    public Effect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        segments = List.copyOf(segments);
    }
}
