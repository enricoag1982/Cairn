/**
 * The in-memory store: entities and relationships as keys and values on an ordered key-value store, with history on two
 * time axes (the {@code Ledger}, ADR-0027), the application and validation of writes, the integrity of relationships,
 * the rules that run at commit, and views (design D3 to D7 of {@code add-entity-versions}, D2 to D5 of
 * {@code add-relationships}, design of {@code refactor-storage-onto-key-value-store}). Not exported; the entry points
 * are {@code Stores} and {@code StoreSettings} in the package above.
 */
package io.github.enricoag1982.cairn.core.data;
