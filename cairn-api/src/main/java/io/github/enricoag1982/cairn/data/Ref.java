package io.github.enricoag1982.cairn.data;

/**
 * A reference to an entity or a relationship in a write: either its {@link Id} or, for one that the same transaction
 * creates, its {@link TemporaryId} (capabilities {@code transactions} and {@code relationships}).
 */
public sealed interface Ref permits Id, TemporaryId {
}
