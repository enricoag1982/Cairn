package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.Objects;

/**
 * An entity or a relationship as the {@link Ledger} holds it at some basis: its identifier and type, which never change,
 * for a relationship the identifiers of its two ends, which never change either, and the {@link Timeline} that was known
 * after the last transaction at or below the basis that changed it. Immutable; a reader keeps no reference to the store.
 *
 * @param id       the identifier: an {@link EntityId} or a {@link RelationshipId}
 * @param type     the identifier of its type
 * @param source   the entity at the source end of a relationship; null for an entity
 * @param target   the entity at the target end of a relationship; null for an entity
 * @param basis    the basis of the last transaction at or below the one that was asked for that changed it
 * @param timeline its effective timeline after that transaction
 */
record Held(Id id, TypeId type, EntityId source, EntityId target, Basis basis, Timeline timeline) {

    Held {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(timeline, "timeline");
    }

    boolean isRelationship() {
        return id instanceof RelationshipId;
    }

    EntityId entityId() {
        return (EntityId) id;
    }

    RelationshipId relationshipId() {
        return (RelationshipId) id;
    }
}
