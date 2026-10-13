package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.Objects;

/**
 * A relationship in the store, kept like an entity (design D2 of {@code add-relationships}): its identifier, its type,
 * the identifiers of its two ends, none of which ever change, and an append-only map from basis to the
 * {@link Timeline} that was known after the transaction of that basis. The relationship's lifetime is the union of the
 * segments of a timeline.
 */
final class StoredRelationship extends Stored<RelationshipId> {

    private final EntityId source;
    private final EntityId target;

    StoredRelationship(RelationshipId id, TypeId type, EntityId source, EntityId target) {
        super(id, type);
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
    }

    EntityId source() {
        return source;
    }

    EntityId target() {
        return target;
    }
}
