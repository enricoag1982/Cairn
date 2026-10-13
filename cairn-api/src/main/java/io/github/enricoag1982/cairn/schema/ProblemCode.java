package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * Stable codes for the reasons a schema definition or registration is rejected (capabilities {@code type-model} and {@code schema-evolution}).
 * Each code has an {@linkplain #explanation() explanation} that can be read at runtime.
 */
public enum ProblemCode {
    /** A name does not start with a letter, contains other characters than letters, digits and underscores, or is too long. */
    INVALID_NAME("A name of a type, attribute or alias must start with a letter, contain only letters, digits and "
            + "underscores, and have at most 128 characters. Rename the element."),
    /** Two elements use the same name or alias, compared without regard to case. */
    DUPLICATE_NAME("Two types, or two navigation names of relationship types, use the same name or alias. Names are "
            + "compared without regard to case. Rename one of them."),
    /** A supertype, trait or relationship end names nothing in the schema. */
    UNKNOWN_REFERENCE("A supertype, included trait or relationship end names a type that does not exist in the schema. "
            + "Correct the name, or define the type."),
    /** An entity type's supertype is not an entity type. */
    SUPERTYPE_NOT_ENTITY_TYPE("The supertype of an entity type must be an entity type, not a trait or a relationship "
            + "type. Name an entity type, or use a trait for shared attributes."),
    /** A relationship type's supertype is not a relationship type. */
    SUPERTYPE_NOT_RELATIONSHIP_TYPE("The supertype of a relationship type must be a relationship type. Name a "
            + "relationship type."),
    /** Supertypes form a cycle. */
    SUPERTYPE_CYCLE("A type is its own ancestor through its supertypes. Break the cycle by changing one supertype."),
    /** An entity type includes something that is not a trait. */
    NOT_A_TRAIT("An entity type can include only traits. Name a trait, or make the type its supertype."),
    /** A relationship type's source or target is not an entity type. */
    RELATIONSHIP_END_NOT_ENTITY_TYPE("The source and the target of a relationship type must be entity types. Name an "
            + "entity type."),
    /** Two attributes of the same type have the same name or alias. */
    DUPLICATE_ATTRIBUTE("Attribute names, with their aliases, must be unique across a type, its supertypes and the "
            + "traits they include. Rename or remove one of the attributes."),
    /** A range is declared on a string or boolean attribute. */
    RANGE_NOT_ALLOWED("Only attributes of type int, long, decimal, date and instant can have a range. Remove the "
            + "range, or use allowed values, a pattern or lengths for a string."),
    /** A range bound does not fit the attribute's value type. */
    RANGE_LITERAL_MISMATCH("The bounds of a range must have the attribute's value type; an int fits a long or decimal "
            + "attribute, and a long fits a decimal attribute. Change the bound."),
    /** No value fits a range: its lower bound is above its upper bound, or equal to it while a bound is exclusive. */
    RANGE_EMPTY("At least one value must fit a range: the lower bound is below the upper bound, or equal to it with "
            + "both bounds inclusive. Change a bound."),
    /** A composition does not give each target exactly one source. */
    COMPOSITION_MULTIPLICITY("In a composition, each target has exactly one source. Set the sources per target to "
            + "exactly one, or make the relationship type an association."),
    /** A composition is marked symmetric. */
    COMPOSITION_SYMMETRIC("A composition has a source that owns its target, so it cannot be symmetric. Remove the "
            + "symmetric flag, or make the relationship type an association."),
    /** A symmetric relationship type has different ends or different multiplicities. */
    SYMMETRIC_ENDS("The two ends of a symmetric relationship type are interchangeable, so they need the same entity "
            + "type and the same multiplicity. Make the ends equal, or remove the symmetric flag."),
    /** A relationship subtype's source or target is not within its supertype's. */
    RELATIONSHIP_SUBTYPE_ENDS("The source and target of a relationship subtype must be the same as, or subtypes of, "
            + "those of its supertype. Change the ends, or the supertype."),
    /** The origin's name or version is malformed. */
    INVALID_ORIGIN("An origin's name may contain only letters, digits, dots, hyphens and underscores, and its version "
            + "must be numbers separated by dots, such as 1.4.0. Correct the origin."),
    /** A registration defines a type that another origin owns. */
    OWNED_BY_OTHER_ORIGIN("Each type belongs to the origin that registered it first. Another origin cannot define a "
            + "type with the same name or alias. Rename the type, or register it from its owner."),
    /** A changed definition carries the same version as the stored one. */
    VERSION_NOT_INCREASED("A registration that differs from the stored definition of its origin needs a higher "
            + "version. Raise the version of the origin."),
    /** A registration contains a breaking change. */
    BREAKING_CHANGE("The registration would break existing data or users: for example it removes or narrows "
            + "something, or makes something stricter. The hint says how to avoid it. Make the change compatible, or "
            + "wait for migrations."),
    /** Allowed values, a pattern or a length is declared on an attribute that is not a string. */
    TEXT_CONSTRAINT_NOT_ALLOWED("Allowed values, patterns and lengths are for string attributes only. Remove the "
            + "constraint, or change the attribute's value type to string."),
    /** A pattern is not a valid I-Regexp (RFC 9485). */
    INVALID_PATTERN("A pattern must be written in I-Regexp (RFC 9485), a portable syntax without shortcuts such as \\d, "
            + "anchors, lookaround or back-references. To keep matching fast, repeat counts are at most 1000, a pattern "
            + "has at most 10,000 elements once repeats are expanded and at most 500 groups, alternatives and optional "
            + "repeats, groups nest at most 100 deep, and \\p{Cn} is not supported. Correct the pattern; the hint "
            + "suggests a replacement where there is one."),
    /** A length is negative, or the minimum length is above the maximum length. */
    INVALID_LENGTH("A minimum or maximum length cannot be negative, and the minimum cannot exceed the maximum. "
            + "Correct the lengths."),
    /** An allowed value is listed twice. */
    DUPLICATE_ALLOWED_VALUE("The allowed values of an attribute must be distinct. Remove the repeated value."),
    /** An example does not fit the attribute's value type or breaks one of its constraints. */
    INVALID_EXAMPLE("Every example of an attribute must have the attribute's value type and satisfy its range, allowed "
            + "values, pattern and lengths. Correct or remove the example."),
    /** A rule name breaks the naming rule for rules, or a rule version is below 1. */
    INVALID_RULE_NAME("A rule name consists of lowercase ASCII letters, digits and hyphens, starts with a letter and "
            + "has at most 128 characters, and a rule version is a whole number of at least 1. Correct the reference."),
    /** No available rule has the name and version that a type names. */
    UNKNOWN_RULE("A type names a rule, in a version, that the schema registry does not have. Correct the name or "
            + "version; the registry lists the available rules."),
    /** The available rule applies to a type that is neither the naming type nor one of its supertypes. */
    RULE_TYPE_MISMATCH("A rule is for one type, and a type may name it only if the rule is for that type or for one "
            + "of its supertypes. Name the rule on a type it applies to."),
    /** A type names a rule that it or one of its supertypes already names. */
    DUPLICATE_RULE("A rule that a type names also applies to its subtypes, so a type and its supertypes cannot name "
            + "the same rule twice. Remove the repeated reference.");

    private final String explanation;

    ProblemCode(String explanation) {
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    /**
     * Explains what the code means and how to resolve it.
     *
     * @return the explanation, never empty
     */
    public String explanation() {
        return explanation;
    }
}
