package io.github.enricoag1982.cairn.schema;

/**
 * Whether a relationship type is a composition or an association (capability {@code type-model}).
 */
public enum RelationshipKind {
    /** The target cannot exist without its source and ends together with it. */
    COMPOSITION,
    /** A plain link between independent entities. */
    ASSOCIATION
}
