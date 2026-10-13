package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;

/**
 * A {@link Store} that is kept on disk, in a directory (ADR-0011, ADR-0022, capability {@code durability}). Every
 * committed transaction, of data or of the schema, is on disk before its commit returns, and a store opened again on the
 * directory has the state of the committed transactions. In every other way it behaves as an in-memory store.
 *
 * <p>One open store holds a directory at a time, in any process. {@link #close()} releases it. After it, every call but
 * {@code close} throws an {@code IllegalStateException}, and so does every call of the store's schema registry.
 *
 * <p>If writing a commit to disk fails, the commit throws an {@code UncheckedIOException}, nothing of the transaction
 * is applied, and {@link #commit(Transaction) commit} and {@code schema().register} throw an
 * {@code IllegalStateException} from then on: the store has to be opened again. Reads, views and dry runs go on.
 */
public interface DurableStore extends Store, AutoCloseable {

    /**
     * Writes a snapshot file of the state at the latest basis, so that opening the directory again reads the snapshot
     * and only the log after it. Commits go on while the file is written, and are not part of it. The file is written to
     * a temporary file, forced to disk and renamed, so a crash leaves either the whole file or none. Afterwards the
     * store keeps the two newest snapshot files and the log needed to recover from the older of them, and deletes
     * the rest.
     *
     * @return the basis of the snapshot, which is the latest basis when the call began; {@link Basis#NONE} for a
     *         store that has nothing committed
     * @throws java.io.UncheckedIOException if writing the file fails
     * @throws IllegalStateException        if the store is closed
     */
    Basis writeSnapshot();

    /**
     * Writes a snapshot file of the latest state, like {@link #writeSnapshot()}, then releases the directory so that
     * another store can be opened on it. Commits that are running finish first. Calling it again does nothing.
     *
     * @throws java.io.UncheckedIOException if writing the snapshot fails; the directory is released all the same, and
     *                                      the log has every committed transaction, so opening the directory again
     *                                      recovers it
     */
    @Override
    void close();
}
