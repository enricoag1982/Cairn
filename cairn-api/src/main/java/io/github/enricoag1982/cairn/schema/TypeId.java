package io.github.enricoag1982.cairn.schema;

/**
 * The schema identifier of an entity type, trait or relationship type (capability {@code schema-evolution}). It is local to a store, never changes and is
 * never reused.
 *
 * @param value the number
 */
public record TypeId(long value) {
}
