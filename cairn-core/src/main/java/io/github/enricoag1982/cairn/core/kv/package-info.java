/**
 * The ordered key-value interface that the engine stores entities and relationships on (ADR-0011, ADR-0027, design D1 of
 * {@code refactor-storage-onto-key-value-store}): byte-string keys and values, compared as unsigned bytes, with get, a
 * range scan in order, and an atomic batch of puts and deletes. Implementations know nothing of the model. Not exported.
 */
package io.github.enricoag1982.cairn.core.kv;
