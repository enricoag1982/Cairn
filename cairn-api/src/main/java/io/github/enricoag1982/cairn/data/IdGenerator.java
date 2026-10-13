package io.github.enricoag1982.cairn.data;

/**
 * Generates the IDs of the entities and relationships of one store, and says which IDs the store accepts (ADR-0019,
 * capabilities {@code transactions} and {@code relationships}). A store has exactly one generator, chosen when the
 * store is created, and it uses it for both kinds of IDs: the generator returns the text of an ID, and the store wraps
 * it as an {@link EntityId} or a {@link RelationshipId}.
 *
 * <p>A generator is a small state machine: {@link #next()} advances it, and {@link #copy()} duplicates its state. A
 * store does not know whether a transaction commits until it has validated it, and validation needs the IDs of the
 * entities and relationships the transaction creates. It therefore draws them from a copy. A dry run and a rejected
 * transaction throw the copy away, so they reserve no ID; a committed transaction makes the copy the store's generator.
 * Two generators in the same state return the same IDs, except for bits that come from a shared source of
 * randomness.
 *
 * <p>A store calls its generator only while it holds its writer lock. The generators of the engine are also safe to
 * use from several threads.
 */
public interface IdGenerator {

    /**
     * Generates the next ID and advances the generator. The text is {@linkplain #accepts(String) accepted} by this
     * generator and has not been returned before by this generator.
     *
     * @return the text form of the ID, never null
     */
    String next();

    /**
     * Tells whether a text is an ID in the format of this generator. A store accepts a supplied ID only if it does.
     * The check concerns the format only: whether the ID exists is for the store to find out.
     *
     * @param text the text form of an ID
     * @return {@code true} if the text has the format; {@code false} for any other text, including null
     */
    boolean accepts(String text);

    /**
     * Copies the generator. The copy is in the same state as this generator at this moment, and is independent: drawing
     * IDs from the copy does not affect this generator, and the other way around.
     *
     * <p>Where a generator draws on a shared source of randomness, such as a random number generator, the copy draws on
     * the same source, so the numbers that it draws are not available to this generator.
     *
     * @return the copy
     */
    IdGenerator copy();
}
