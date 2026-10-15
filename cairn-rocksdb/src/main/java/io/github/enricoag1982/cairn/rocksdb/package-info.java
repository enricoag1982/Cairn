/**
 * Stores kept in RocksDB (ADR-0028, capability {@code durability}): the entry point, {@link
 * io.github.enricoag1982.cairn.rocksdb.RocksDbStores}, and the backend that implements the engine's persistent key-value
 * interface on a RocksDB database.
 */
package io.github.enricoag1982.cairn.rocksdb;
