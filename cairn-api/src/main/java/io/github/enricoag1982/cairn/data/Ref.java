package io.github.enricoag1982.cairn.data;

/**
 * A reference to an entity or a relationship in a write: its {@link Id}; for one that the same transaction creates, its
 * {@link TemporaryId}; or, for an existing entity, a {@link UniqueKey} that the store resolves to the entity's ID when it
 * commits (capabilities {@code transactions} and {@code relationships}, ADR-0026).
 */
public sealed interface Ref permits Id, TemporaryId, UniqueKey {
}
