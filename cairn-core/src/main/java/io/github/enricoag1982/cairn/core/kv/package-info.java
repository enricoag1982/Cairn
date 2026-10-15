/**
 * The ordered key-value interface that the engine stores entities and relationships on (ADR-0011, ADR-0027, design D1 of
 * {@code refactor-storage-onto-key-value-store}): byte-string keys and values, compared as unsigned bytes, with get, a
 * range scan in order, and an atomic batch of puts and deletes. Implementations know nothing of the model. The package
 * also holds the persistent variant of the interface, which a backend on disk implements, and the factory of stores over
 * it (ADR-0028, design D1 of {@code add-rocksdb-store}). It is exported only to the module of the RocksDB store,
 * {@code io.github.enricoag1982.cairn.rocksdb}.
 */
package io.github.enricoag1982.cairn.core.kv;
