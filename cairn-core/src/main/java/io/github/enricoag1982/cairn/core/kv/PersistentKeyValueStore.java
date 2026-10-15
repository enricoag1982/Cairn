package io.github.enricoag1982.cairn.core.kv;

/**
 * A {@link KeyValueStore} that keeps its keys on stable storage (ADR-0028, design D1 of {@code add-rocksdb-store}): the
 * backend of a store whose files a database holds, such as RocksDB. The engine writes everything a commit changes, its
 * ledger keys and its metadata, as one {@link Batch}, so {@link #apply(Batch)} is the commit point: when it returns, the
 * batch survives a crash, and a crash during the call leaves all of the batch or none of it.
 *
 * <p>The engine opens a {@linkplain KeyValueStores#openDurable store over it}, which owns it from then on: it calls
 * {@link #close()} when the store is closed. One thread at a time applies batches; any number of threads read.
 */
public interface PersistentKeyValueStore extends KeyValueStore, AutoCloseable {

    /**
     * Applies the operations of a batch in order as one atomic change, and returns only when the batch is durable.
     *
     * @param batch the batch
     * @throws java.io.UncheckedIOException if writing fails; the engine then treats the batch as not applied, and
     *                                      refuses further commits until the store is opened again, though a batch
     *                                      that reached the disk whole is applied when the store is opened again
     * @throws IllegalStateException        if the store is closed
     */
    @Override
    void apply(Batch batch);

    /**
     * Writes what the store holds in memory to its files, and returns when it is written.
     *
     * @throws java.io.UncheckedIOException if writing fails
     * @throws IllegalStateException        if the store is closed
     */
    void flush();

    /**
     * Compacts every key of the store, so that no file keeps a value or a key that was overwritten or deleted, including
     * the store's own records of its files, such as a manifest that names the smallest and the largest key of each of them,
     * and returns when it is done. A purge calls it after it deleted what it removes (ADR-0024). It takes time in
     * proportion to the size of the store. A scan that is running while it compacts goes on with the keys that are left,
     * after the last key it returned.
     *
     * @throws java.io.UncheckedIOException if compacting fails
     * @throws IllegalStateException        if the store is closed
     */
    void compactAll();

    /**
     * Releases the store's files and whatever else it holds, such as the lock of its directory. Closing a store again
     * does nothing; every other call of a closed store throws an {@code IllegalStateException}.
     *
     * @throws java.io.UncheckedIOException if releasing fails; the store is closed all the same
     */
    @Override
    void close();
}
