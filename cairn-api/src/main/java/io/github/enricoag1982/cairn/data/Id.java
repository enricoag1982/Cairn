package io.github.enricoag1982.cairn.data;

/**
 * The ID of something a store holds. It never changes, and callers treat it as opaque: they compare and store it, but
 * never read meaning from it (ADR-0019, capability {@code transactions}).
 *
 * <p>Today only entities have IDs; relationships will get their own kind with the capability that adds them.
 */
public sealed interface Id extends Ref permits EntityId {

    /**
     * The canonical text form of the ID, in the format of the store's {@link IdGenerator}.
     *
     * @return the text, never null
     */
    String value();
}
