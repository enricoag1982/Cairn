package io.github.enricoag1982.cairn.data;

/**
 * A reference to an entity in a write: either its {@link Id} or, for an entity that the same transaction creates, its
 * {@link TemporaryId} (capability {@code transactions}).
 */
public sealed interface Ref permits Id, TemporaryId {
}
