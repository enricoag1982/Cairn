package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.schema.TypeId;

/**
 * An entity in the store: its identifier, its type, which never changes, and an append-only map from basis to the
 * {@link Timeline} that was known after the transaction of that basis (design D4 of {@code add-entity-versions}).
 */
final class StoredEntity extends Stored<EntityId> {

    StoredEntity(EntityId id, TypeId type) {
        super(id, type);
    }
}
