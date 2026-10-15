package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;

/**
 * A {@link Store} that is kept on disk, in a directory (ADR-0011, ADR-0022, ADR-0028, capability {@code durability}). Every
 * committed transaction, of data or of the schema, is on disk before its commit returns, and a store opened again on the
 * directory has the state of the committed transactions. In every other way it behaves as an in-memory store.
 *
 * <p>A {@linkplain #purge(Purge) purge} is made durable before it returns (ADR-0024, capability {@code purge}): the store
 * writes it to its commit log, writes a snapshot file without the purged data, and deletes every older snapshot file and
 * log segment, so that no file of the store holds the purged data. Commits wait while a purge runs, which takes time
 * in proportion to the size of the store. If the store stops after the purge reached its log, opening the directory
 * again completes the purge before the store opens. If making the purge durable fails, the call throws an
 * {@code UncheckedIOException}, the purge is applied in memory, and the store refuses commits like after a failed write,
 * until it is opened again and completes the purge.
 *
 * <p>A store kept in RocksDB ({@code RocksDbStores.open} in the module {@code cairn-rocksdb}, ADR-0028) implements this
 * interface, and differs from what is described above in these ways:
 *
 * <ul>
 *   <li>It has no commit log segments or snapshot files: each commit is a synchronous write batch to RocksDB's
 *       write-ahead log, and {@link #writeSnapshot()} and {@link #close()} flush what RocksDB holds in memory to its
 *       files, so that opening the store again replays no write-ahead log. A store that refuses writes after a failure
 *       does not flush when it closes.
 *   <li>A purge is one synchronous write, which a flush and a compaction of all of the store follow before the purge
 *       returns, so that no file of the store holds the purged data, not even the keys that named it. A purge whose write
 *       cannot be made applies nothing. If the flush, the compaction or the reopening of the database that follows it
 *       fails, the purge is applied, the call throws, and the store refuses commits; opening the store finishes the
 *       purge.
 *   <li>After the database could not be reopened, every call but {@code close} throws, until the store is opened again.
 * </ul>
 *
 * <p>One open store holds a directory at a time, in any process. {@link #close()} releases it. A closed store refuses
 * every call but {@code close}, which does nothing the second time: each call throws an {@code IllegalStateException},
 * and so does every call of the store's schema registry.
 *
 * <p>If writing a commit to disk fails, the commit throws an {@code UncheckedIOException}, nothing of the transaction
 * is applied, and {@link #commit(Transaction) commit} and {@code schema().register} throw an
 * {@code IllegalStateException} from then on: the store has to be opened again. Reads, views and dry runs go on.
 *
 * <p>A failed write can still leave the record of the failed commit on disk, whole: the store did not apply it, but the
 * next opening recovers it, as it recovers any record that is complete. A caller that retries a transaction after a
 * failed write, in the store opened again, should give it an idempotency key, so that the retry gets the receipt of
 * the record that was recovered instead of recording the transaction twice.
 */
public interface DurableStore extends Store, AutoCloseable {

    /**
     * Writes a snapshot file of the state at the latest basis, so that opening the directory again reads the snapshot
     * and only the log after it. Commits go on while the file is written, and are not part of it. The file is written to
     * a temporary file, forced to disk and renamed, so a crash leaves either the whole file or none. Afterwards the
     * store keeps the two newest valid snapshot files and the log needed to recover from the older of them, and
     * deletes the rest.
     *
     * @return the basis of the snapshot, which is the latest basis when the call began; {@link Basis#NONE} for a
     *         store that has nothing committed
     * @throws java.io.UncheckedIOException if writing the file fails
     * @throws IllegalStateException        if the store is closed
     */
    Basis writeSnapshot();

    /**
     * Writes a snapshot file of the latest state, like {@link #writeSnapshot()}, then releases the directory so that
     * another store can be opened on it. Commits that are running finish first. Closing completes the subscriptions to the
     * store's {@linkplain Store#changeFeed(Basis) change feed} (ADR-0029). Calling it again does nothing.
     *
     * @throws java.io.UncheckedIOException if writing the snapshot fails; the directory is released all the same, and
     *                                      the log has every committed transaction, so opening the directory again
     *                                      recovers it
     */
    @Override
    void close();
}
