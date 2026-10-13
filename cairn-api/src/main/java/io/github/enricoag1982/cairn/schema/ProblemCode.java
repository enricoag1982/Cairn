package io.github.enricoag1982.cairn.schema;

/**
 * Stable codes for the reasons a schema definition or registration is rejected (capabilities {@code type-model} and {@code schema-evolution}).
 */
public enum ProblemCode {
    /** A name does not start with a letter, contains other characters than letters, digits and underscores, or is too long. */
    INVALID_NAME,
    /** Two elements use the same name or alias, compared without regard to case. */
    DUPLICATE_NAME,
    /** A supertype, trait or relationship end names nothing in the schema. */
    UNKNOWN_REFERENCE,
    /** An entity type's supertype is not an entity type. */
    SUPERTYPE_NOT_ENTITY_TYPE,
    /** A relationship type's supertype is not a relationship type. */
    SUPERTYPE_NOT_RELATIONSHIP_TYPE,
    /** Supertypes form a cycle. */
    SUPERTYPE_CYCLE,
    /** An entity type includes something that is not a trait. */
    NOT_A_TRAIT,
    /** A relationship type's source or target is not an entity type. */
    RELATIONSHIP_END_NOT_ENTITY_TYPE,
    /** Two attributes of the same type have the same name or alias. */
    DUPLICATE_ATTRIBUTE,
    /** A range is declared on a string or boolean attribute. */
    RANGE_NOT_ALLOWED,
    /** A range bound does not fit the attribute's value type. */
    RANGE_LITERAL_MISMATCH,
    /** No value fits a range: its lower bound is above its upper bound, or equal to it while a bound is exclusive. */
    RANGE_EMPTY,
    /** A composition does not give each target exactly one source. */
    COMPOSITION_MULTIPLICITY,
    /** A composition is marked symmetric. */
    COMPOSITION_SYMMETRIC,
    /** A symmetric relationship type has different ends or different multiplicities. */
    SYMMETRIC_ENDS,
    /** A relationship subtype's source or target is not within its supertype's. */
    RELATIONSHIP_SUBTYPE_ENDS,
    /** The origin's name or version is malformed. */
    INVALID_ORIGIN,
    /** A registration defines a type that another origin owns. */
    OWNED_BY_OTHER_ORIGIN,
    /** A changed definition carries the same version as the stored one. */
    VERSION_NOT_INCREASED,
    /** A registration contains a breaking change. */
    BREAKING_CHANGE
}
