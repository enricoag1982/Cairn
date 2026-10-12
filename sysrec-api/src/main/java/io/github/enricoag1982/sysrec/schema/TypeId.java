package io.github.enricoag1982.sysrec.schema;

/**
 * The schema identifier of an entity type, trait or relationship type. It is local to a store, never changes and is
 * never reused.
 *
 * @param value the number
 */
public record TypeId(long value) {
}
