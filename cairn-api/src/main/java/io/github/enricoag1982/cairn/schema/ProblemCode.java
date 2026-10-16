package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * Stable codes for the reasons a schema definition or registration is rejected (capabilities {@code type-model},
 * {@code checks}, {@code derived-attributes} and {@code schema-evolution}), for the reasons a schema document
 * (capability {@code schema-documents}) or a transaction payload (capability {@code json-payloads}) cannot be read, for
 * the reasons a data transaction is rejected (capabilities {@code transactions}, {@code entity-versions},
 * {@code entity-rules}, {@code checks}, {@code derived-attributes} and {@code relationships}), for the reasons a purge
 * is refused (capability {@code purge}), for the reasons a request of an AI agent is refused (capability
 * {@code agent-tools}), and for the reasons a query does not fit the schema of the view that runs it (capability
 * {@code queries}).
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
            + "the same rule twice. Remove the repeated reference."),
    /** A check name breaks the naming rule for checks. */
    INVALID_CHECK_NAME("A check name consists of lowercase ASCII letters, digits and hyphens, starts with a letter and "
            + "has at most 128 characters, as a rule name does. Rename the check."),
    /** A type declares a check whose name it, or one of its supertypes, already uses for a check, an attribute or a rule. */
    DUPLICATE_CHECK("A check that a type declares also applies to its subtypes, so its name must be used once in the "
            + "hierarchy: a type cannot declare two checks with the same name, neither can a type and one of its "
            + "supertypes, and a check cannot be named like an attribute or a rule of the type or of one of its "
            + "supertypes. The message says what takes the name. Rename or remove the check."),
    /** The expression of a check is blank, too long, does not compile, reads an attribute the type lacks, or is no boolean. */
    INVALID_CHECK("The expression of a check must be an expression of the Common Expression Language (CEL) with at "
            + "most 4,000 characters that compiles, reads the version's attributes only as self.<name> with names the "
            + "type has, and whose result is a boolean. The message gives the error and, where it is known, the line "
            + "and column in the expression, counted from 1, as \"at line L, column C\"; the found details give the "
            + "expression. For a check in a schema document, the problem is also located in the document, at that "
            + "position where the text allows it. Correct the expression."),
    /** The expression of a derived attribute does not compile, reads an attribute it must not, or has the wrong type. */
    INVALID_DERIVATION("The expression of a derived attribute must be an expression of the Common Expression Language "
            + "(CEL) with at most 4,000 characters that compiles, reads the version's attributes only as "
            + "self.<name> with names the type has and that are not derived, and whose result can be a value of the "
            + "attribute's type: a string for a string attribute, a bool for a boolean, an int for an int or long, a "
            + "double for a decimal, a timestamp for an instant or a date. An optional attribute may also give null. "
            + "The path is the type that declares the attribute and the attribute's name; the message gives the error "
            + "and, where it is known, the line and column in the expression, counted from 1, as \"at line L, column "
            + "C\"; the found details give the expression. For an attribute in a schema document, the problem is also "
            + "located in the document, at the key derived and, where the text allows it, at that position. Correct "
            + "the expression."),
    /** A registry or a store has no expression language, so it cannot compile or run checks or derived attributes. */
    EXPRESSIONS_UNAVAILABLE("A definition declares checks or derived attributes, or a write concerns a type to which "
            + "checks apply or that has derived attributes, but there is no expression language to compile or run "
            + "them. Put the module cairn-cel on the class path, or give the settings an expression language; until "
            + "then a schema registry rejects definitions that declare checks or derived attributes, and a store "
            + "rejects every write of an entity or relationship of a type to which a check applies or that has a "
            + "derived attribute, whatever the write changes."),
    /** A schema document or a transaction payload is not valid YAML 1.2, or JSON. */
    DOCUMENT_SYNTAX("The text of a schema document or of a transaction payload is not valid YAML 1.2 or JSON, so it "
            + "cannot be read at all. Reading stops at the first syntax error; fix the syntax at the given line and "
            + "column, and read the text again. An agent tool reports it too, with the path @arguments, when the "
            + "arguments of the call are not valid JSON. A query reports it too, with the path @query."),
    /** A schema document or a transaction payload declares a format version that this reader does not know. */
    UNSUPPORTED_FORMAT_VERSION("This reader knows schema documents and transaction payloads of format version 1, "
            + "written as 'cairn: 1'. Set the key cairn to 1, or use a reader for the version that the text "
            + "declares. A query that declares another format version than 1 gets it too."),
    /** A schema document or a transaction payload uses a YAML feature outside the supported subset. */
    UNSUPPORTED_YAML_FEATURE("Schema documents and transaction payloads use a safe subset of YAML 1.2, without "
            + "anchors, aliases, merge keys and explicit tags, and with one document in a stream. Write the value out "
            + "in full, and remove the tag or the extra document. A query is read the same way."),
    /** A mapping in a schema document or a transaction payload has the same key twice. */
    DUPLICATE_KEY("A mapping in a schema document or a transaction payload has the same key twice, for example a type "
            + "that is defined twice, or an attribute that is given twice in the values of a write. Remove one of "
            + "them, or rename it. A query is read the same way."),
    /** A key in a schema document or a transaction payload is not one that is known at that position. */
    UNKNOWN_KEY("A key is not one of the keys that a schema document or a transaction payload knows at that "
            + "position, for example a kind of write that does not exist. The expected details list the known keys, "
            + "and the hint names the closest one. Correct the key, or remove it. An agent tool reports it too, with a "
            + "path such as @arguments.id, for an argument that it does not take, or for a key of knownAt other than "
            + "basis and transactionTime. A query reports it too, with a path such as "
            + "@query.steps.1, for a key that it does not know."),
    /** A key that a schema document or a transaction payload requires at a position is missing. */
    MISSING_KEY("A key that is required at that position is missing, for example the source, the target, the inverse "
            + "name and both multiplicities of a relationship type, or the principal and the writes of a transaction "
            + "payload. Add the key. An agent tool reports it too, with a path such as @arguments.id, when an argument "
            + "that it needs is missing. A query reports it too, with a path such as "
            + "@query.from, for a key that it needs."),
    /** A value in a schema document or a transaction payload has the wrong kind, or is written in a form that is not allowed. */
    INVALID_VALUE("A value in a schema document or a transaction payload has the wrong kind, for example a number "
            + "where text is expected, or is written in a form that is not allowed, for example a multiplicity other "
            + "than 0..1, 1..1, 0..* or 1..*, or an instant that is not ISO 8601 with Z or an offset, such as "
            + "2026-01-05T09:00:00Z or 2026-01-05T10:00:00+01:00, which is converted to UTC. The expected and found "
            + "details say which. Correct the value; put text that YAML would read as another kind, such as "
            + "\"1.10\" or \"true\", in quotes. An agent tool reports it too when an argument has the wrong kind or "
            + "form, such as an instant that is not ISO 8601 or a negative maximum depth, or names nothing that "
            + "exists, such as a problem code or an approval request. A query reports it too, with a path such as "
            + "@query.limit, for a value of the wrong kind or form, such as a negative limit."),
    /** A write creates an entity of a name that is not an entity type. */
    UNKNOWN_ENTITY_TYPE("A write creates an entity of a type name that the schema does not have as an entity type. "
            + "Type names and aliases are compared without regard to case. Correct the name, which the hint suggests, "
            + "or register the type first. An agent tool reports it too when it is asked for the entities, or the "
            + "JSON Schema of the values, of a type name that the schema does not have. A query reports it too when "
            + "it starts from, or tests for, a type name that the schema does not have as an entity type."),
    /** A write creates an entity of an abstract entity type. */
    ABSTRACT_ENTITY_TYPE("An abstract entity type has no entities of its own: it only groups its subtypes. Create the "
            + "entity as one of the concrete subtypes."),
    /** A write sets or replaces a value for a name that is not an attribute of the entity's or relationship's type. */
    UNKNOWN_ATTRIBUTE("A value is given for a name that is not an attribute, or an alias of one, of the type of the "
            + "entity or relationship, including the attributes of its supertypes and traits. Correct the name, "
            + "which the hint suggests. A query reports it too for an attribute that the type of a binding does not "
            + "have."),
    /** A value does not have the attribute's value type. */
    VALUE_TYPE_MISMATCH("A value must have the value type of its attribute; an int fits a long or decimal attribute, "
            + "and a long fits a decimal attribute. The expected and found details name the types. Give a value of "
            + "the expected type."),
    /** A value lies outside the attribute's range. */
    VALUE_OUT_OF_RANGE("A value must lie in the range that its attribute declares, respecting inclusive and "
            + "exclusive bounds. The expected and found details give the range and the value. Give a value in the "
            + "range."),
    /** A string value is not one of the attribute's allowed values. */
    VALUE_NOT_ALLOWED("The attribute accepts only the values it lists, compared exactly. The expected details list "
            + "them. Give one of the allowed values."),
    /** A string value does not match the attribute's pattern. */
    VALUE_PATTERN_MISMATCH("A string value must match the attribute's pattern as a whole. The expected details give "
            + "the pattern. Correct the value, for example its case or its length."),
    /** A string value is shorter than the attribute's minimum length or longer than its maximum length. */
    VALUE_LENGTH("A string value must have at least the minimum and at most the maximum length of its attribute, "
            + "counted in Unicode code points. The expected details give the lengths. Shorten or lengthen the value."),
    /** An attribute that is required has no value in some part of the timeline of an entity or relationship. */
    MISSING_REQUIRED_VALUE("A required attribute must have a value at every point in effective time in which the "
            + "entity or relationship exists, also after a replace or a clear. Give the attribute a value for the "
            + "whole period."),
    /** A write sets an attribute that is deprecated. */
    DEPRECATED_ATTRIBUTE("A deprecated attribute should no longer be used, so new writes may not set it; values "
            + "written earlier stay readable. Remove the value from the write, or use the attribute that replaces it."),
    /** Another entity or relationship has the same value of a unique attribute at some point in effective time. */
    UNIQUE_VALUE_TAKEN("Two entities, or two relationships, would have the same value of a unique attribute at the "
            + "same point in effective time. The check covers all entities or relationships whose type has the "
            + "attribute, including subtypes. Use another value, or change the other one's value, or end it, so that "
            + "the periods no longer overlap."),
    /** A write refers to an ID that no entity has, to change the entity or to relate it. */
    UNKNOWN_ENTITY("A write patches, replaces or end-dates an entity, or relates one as the source or target of a "
            + "relationship, by an ID that no entity of the store has; the ID of a relationship is not the ID of an "
            + "entity. Correct the ID, or create the entity first. An agent tool reports it too when it reads by an ID "
            + "that no entity has (for a timeline, that neither an entity nor a relationship has), or that is the ID "
            + "of a relationship. A purge reports it at @purge.<n>, for an ID of its list that no entity has."),
    /**
     * A patch or replace covers a period in which the entity or relationship does not exist, or an end-date is at or
     * before its start.
     */
    ENTITY_NOT_EFFECTIVE("A patch or replace must lie within the lifetime of the entity or relationship, from its "
            + "creation until its end-date, and an end-date must come after its start. A write never extends a "
            + "lifetime. Change the period or the instant, or create it for the period first."),
    /** A write creates an entity or relationship with an ID that already exists. */
    ENTITY_EXISTS("An entity or relationship with the supplied ID exists already, in the store or earlier in the same "
            + "transaction, or an entity or relationship that had it was removed by a purge. IDs are never reused, "
            + "and are unique across entities and relationships. To change it, patch or replace it; otherwise leave "
            + "the ID out, or supply another."),
    /** A supplied ID is not in the format of the store's ID generator. */
    INVALID_ENTITY_ID("A store accepts only supplied IDs in the format of its ID generator, which is chosen when the "
            + "store is created; UUIDv7 by default, in lowercase hyphenated text. Supply an ID in that format, or "
            + "leave the ID out so that the store generates one."),
    /** A write refers to a temporary ID that no earlier write of the transaction creates. */
    UNKNOWN_TEMPORARY_ID("A temporary ID is valid only in the transaction that creates the entity or relationship, "
            + "and only in writes after the one that creates it. Create it with that temporary ID earlier in the same "
            + "transaction, or refer to it by its ID."),
    /** A unique key does not name an entity type, a unique attribute of it, or a value that fits the attribute. */
    INVALID_UNIQUE_KEY("A unique key names an entity by an entity type, an attribute of that type that is unique, and a "
            + "value that fits the attribute's value type; names and aliases are compared without regard to case. A key "
            + "is invalid if the schema has no such entity type, the type has no such attribute, the attribute is not "
            + "unique, or the value does not fit. The expected details list the unique attributes of the type, where it "
            + "has some. Correct the key, or name the entity by its ID. An agent tool reports it too, at "
            + "@arguments.key, for a key that its call gives."),
    /** No entity has the value of a unique key at the start of the write's period. */
    UNIQUE_KEY_NOT_FOUND("A unique key names no entity: no entity of the key's type, or of one of its subtypes, has the "
            + "value at the start of the write's effective period, as the store held it before the transaction. A "
            + "value can move from one entity to another over time. A key resolves against the store before the "
            + "transaction, so it does not see what the same transaction creates or writes; temporary IDs name what it "
            + "creates. Correct the key or the period of the write, create the entity first, or name an entity that the "
            + "transaction creates by its temporary ID."),
    /** Two writes of a transaction create entities or relationships with the same temporary ID. */
    DUPLICATE_TEMPORARY_ID("A temporary ID names one entity or relationship in a transaction; entities and "
            + "relationships share the same temporary IDs. Give each created entity or relationship its own "
            + "temporary ID."),
    /** An effective period ends at or before its start. */
    INVALID_PERIOD("An effective period is half-open: it includes its start and excludes its end, so the end must be "
            + "after the start. Leave the end out for a period without an end, or correct the instants."),
    /** A conflict check found that the entity changed after the caller read it. */
    CONFLICT("The entity or relationship, or for a per-attribute check an attribute that the write sets or clears (the "
            + "whole entity or relationship for a replace or an end-date), differs from its state at the basis at "
            + "which the caller read it. Read it again, decide whether the write still applies, and submit it with "
            + "the new basis."),
    /** A transaction names no principal. */
    MISSING_PRINCIPAL("Every transaction records who submitted it, so it must name a principal that is not blank. "
            + "Name the principal, for example an agent or a user."),
    /** An idempotency key was used before by a transaction with other content. */
    IDEMPOTENCY_KEY_REUSED("An idempotency key identifies one transaction: a transaction with the same writes, "
            + "principal, party and reason is a retry and gets the first receipt, any other is rejected. Use a new key "
            + "for a new transaction."),
    /** A transaction repeats the idempotency key of a transaction that wrote data which a purge removed. */
    IDEMPOTENCY_KEY_PURGED("The idempotency key was used by a transaction that wrote an entity or a relationship which a "
            + "purge removed later, so the store no longer has that transaction and cannot tell a retry from another "
            + "transaction, and recording it again would bring the purged data back. The key stays used for good, and "
            + "the path is @transaction, like that of a key that was reused. Use a new idempotency key if the "
            + "transaction is meant to be recorded again."),
    /** A rule found that a version of an entity or of a relationship breaks it. */
    RULE_VIOLATION("A rule that the type of an entity or relationship, or one of its supertypes, names found that a "
            + "version of the entity or relationship breaks it. The problem's rule code, qualified by the rule's name, "
            + "says which check failed, and the schema registry lists the codes of every rule with an explanation. "
            + "Change the values, or the other entities and relationships that the rule reads, as the message says."),
    /** A rule threw, tried to write, reported a code or an attribute that it must not, or cannot check its subject. */
    RULE_FAILED("A rule gave no result: it threw an exception or an error, tried to write, reported a code that its "
            + "description does not declare or an attribute that the type of the entity or relationship does not "
            + "have, or is not a rule for the kind of type that names it (an entity rule for a relationship type, or "
            + "a relationship rule for an entity type). The found details give the exception's message, the code or "
            + "the attribute. The fault is in the rule, not in the transaction: whoever maintains the rule has to "
            + "correct it."),
    /** A type names a rule whose code the store does not have. */
    RULE_UNAVAILABLE("A type names a rule that the store declares but has no code for, so every write of an entity "
            + "or relationship of that type, or of one of its subtypes, is rejected, whatever the write changes. "
            + "Create the store with the rule's code, or register a schema in which the type no longer names the "
            + "rule."),
    /** A check found that a version of an entity or of a relationship breaks it. */
    CHECK_VIOLATED("A check that the type of an entity or relationship, or one of its supertypes, declares is false for "
            + "a version of the entity or relationship that the transaction writes. The problem names the check, and "
            + "gives its message or its expression; the path is that of the write or of the entity, and the message "
            + "names the start of the version when the write covers several versions. Change the values so that the "
            + "check holds."),
    /** A check gave no result: its evaluation failed. */
    CHECK_FAILED("A check gave no result for a version that the transaction writes: its evaluation failed, for "
            + "example because the expression reads an attribute that has no value without testing it with has(), or "
            + "because it exceeded the cost limit of 10,000 evaluation steps. It also gives no result if the store's "
            + "expression language could not compile the recorded check when the store was opened, for example "
            + "because it is another version of the language; every write to the types the check applies to then "
            + "fails. The problem names the check and gives the error. Test optional attributes with has(self.name) in "
            + "the expression, or correct the expression in a new version of the schema, or open the store with an "
            + "expression language that compiles it."),
    /** A create, relate, patch, replace or clear names a derived attribute. */
    DERIVED_ATTRIBUTE_WRITTEN("A derived attribute takes no values from writes: a create, relate, patch, replace or "
            + "clear that names one is rejected, and the derivation does not run for the entity or relationship it "
            + "names. The path names the attribute, and in a payload the problem is located at the attribute's value. "
            + "Leave the attribute out of the write: the store computes it from the other values of the version when "
            + "the transaction commits."),
    /** The derivation of an attribute gave no value that fits it: an evaluation error, a result that does not fit. */
    DERIVATION_FAILED("The expression of a derived attribute gave no value that fits it for a version that the "
            + "transaction writes: its evaluation failed, for example because it reads an attribute that has no value "
            + "without testing it with has(), or because it exceeded the cost limit of 10,000 evaluation steps; its "
            + "result does not fit the attribute's value type, for example an integer outside the range of an int, "
            + "a timestamp that is not at the start of a day in UTC for a date, or a double that is not a number; or "
            + "it gave no value, null, for a required attribute. It also fails if the store's expression language "
            + "could not compile the recorded expression when the store was opened; every write to the types that "
            + "have the attribute then fails. The path names the attribute and the message gives the error. Test "
            + "optional attributes with has(self.name) in the expression, correct the expression in a new version of "
            + "the schema, or open the store with an expression language that compiles it."),
    /** A write relates entities with a name that is not a relationship type. */
    UNKNOWN_RELATIONSHIP_TYPE("A write relates two entities with a type name that the schema does not have as a "
            + "relationship type. Type names and aliases are compared without regard to case. Correct the name, which "
            + "the hint suggests, or register the type first. An agent tool reports it too when it navigates or "
            + "traverses by a name that is neither the name nor the inverse name of a relationship type. A query "
            + "reports it too when a step or a condition names such a name."),
    /** The source or the target of a relationship is not an entity of the type that the relationship type needs. */
    WRONG_END_TYPE("The source of a relationship must be an entity of the relationship type's source type, or of one of "
            + "its subtypes, and the target an entity of its target type or of one of its subtypes. For a symmetric "
            + "type either order is accepted. The expected and found details name the types. Relate entities of the "
            + "right types."),
    /** A relationship exists at a time at which one of its ends does not. */
    END_NOT_EFFECTIVE("Both ends of a relationship must exist at every point of the relationship's effective period, "
            + "so a write never relates an entity outside its lifetime. The message names the end and the period in "
            + "which it does not exist, and the found details give the relationship's lifetime. Shorten the "
            + "relationship's period, or create the entity for the period first."),
    /** An entity ends, or has been shortened, while a relationship to it continues. */
    RELATIONSHIP_OUTLIVES_END("A relationship cannot continue after one of its ends has ended. A transaction that "
            + "ends an entity must end every relationship that has the entity as an end, from the same instant or "
            + "from an earlier one; compositions, their targets and the targets' other relationships end by "
            + "themselves. End the relationship in the same transaction, at or before the instant at which the "
            + "entity ends, or end the entity later."),
    /** An entity has fewer relationships of a type than the minimum, at some point in effective time. */
    TOO_FEW_RELATIONSHIPS("An entity must have at least the minimum number of relationships that a relationship type "
            + "declares for its end, counting the relationships of the type and of its subtypes, at every point in "
            + "effective time in which the entity exists. The path names the entity and the relationship type or "
            + "inverse name. Relate the entity for the whole period, in the same transaction if it is new. A purge "
            + "reports it too, at the ID of an entity that the purge would leave without a relationship that its type "
            + "requires, because the purge removes the relationships the entity had with purged entities: purge that "
            + "entity too, or leave out the entities it needs."),
    /** An entity has more relationships of a type than the maximum, at some point in effective time. */
    TOO_MANY_RELATIONSHIPS("An entity must have at most the maximum number of relationships that a relationship type "
            + "declares for its end, counting the relationships of the type and of its subtypes, at every point in "
            + "effective time. The path names the entity and the relationship type or inverse name. End a "
            + "relationship before the next one starts, or do not relate the entity again."),
    /** Two relationships of one type connect the same entities at the same time. */
    DUPLICATE_RELATIONSHIP("Two relationships of the same type must not connect the same source and target at the "
            + "same point in effective time; for a symmetric type the order of the ends does not matter. Change the "
            + "period so that they no longer overlap, or patch the existing relationship instead."),
    /** A write refers to an ID that no relationship has. */
    UNKNOWN_RELATIONSHIP("A write patches, replaces or end-dates a relationship that does not exist, by an ID that no "
            + "relationship of the store has. Correct the ID, or relate the entities first. An agent tool reports it "
            + "too when it reads by an ID that no relationship has, or that is the ID of an entity."),
    /** The approval policy of the store denies an operation that an agent asked for. */
    APPROVAL_DENIED("The approval policy of the store denies this kind of operation to agents, such as registering a "
            + "schema or committing a transaction, so the request is refused and nothing is recorded. The message "
            + "names the operation. Ask a person to change the policy or to make the change, or work with the "
            + "operations that the policy allows."),
    /** A transaction payload names another principal than the adapter that submits it acts as. */
    PRINCIPAL_MISMATCH("An adapter records its own principal on every transaction it submits, so a payload may name "
            + "only that principal; one that names another is refused and nothing is recorded. The expected details "
            + "give the principal of the adapter. Set the principal of the payload to it, and name the party you act "
            + "for in onBehalfOf, if there is one."),
    /** A query names a binding that no earlier step binds, or one of the wrong kind. */
    UNKNOWN_BINDING("A step, condition, group key, aggregate, column or order of a query names a binding that the query "
            + "has not bound by then, or one of the wrong kind, such as the depth of a traversal where an entity is "
            + "needed, or a binding after grouping, when only the keys and the aggregates are left. Names of bindings "
            + "are compared exactly. Correct the name, which the hint suggests, or bind it in an earlier step."),
    /** A query binds a name twice, or gives two keys, aggregates or columns the same name. */
    DUPLICATE_BINDING("A query binds a name that the start or an earlier step has bound already, or gives two group "
            + "keys or aggregates, or two columns, the same name. Names within a query are compared exactly. Rename "
            + "one of them."),
    /** A condition compares values that cannot be compared. */
    INCOMPARABLE_VALUES("A condition compares values that cannot be compared: numbers compare with numbers, strings "
            + "with strings, dates with dates and instants with instants, and booleans compare for equality only. A "
            + "literal takes the value type of the attribute it is compared with where it can, such as an int for a "
            + "decimal attribute, or an ISO 8601 string for a date or an instant. The expected and found details "
            + "name the value types. Compare values of the same kind."),
    /** An aggregate cannot be computed from its attribute. */
    INVALID_AGGREGATE("An aggregate needs an attribute, except a count of rows, and the values must suit the function: "
            + "a sum or an average needs numbers, and a minimum or a maximum needs numbers, dates, instants or "
            + "strings. The expected and found details name the value types. Aggregate another attribute, or count "
            + "the rows.");

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
