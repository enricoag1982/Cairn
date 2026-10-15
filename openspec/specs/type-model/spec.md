# type-model Specification

## Purpose
Defines what a Cairn schema can contain (entity types, traits, attributes and relationship types) and how a schema definition is validated before it is registered.

## Requirements

### Requirement: Schema definitions are plain data

The system SHALL accept a schema definition as plain data that lists entity types, traits and relationship types by name. It SHALL NOT require a Java class, or any other code artifact, to exist for a defined type.

#### Scenario: Defining the reference model without classes

- **WHEN** the reference domain model (Named, Party, Person, Organization, Address, Account, CustomerRole, Owns, Employs, HeldBy, HasAddress, PlayedBy) is described through the schema API, with no corresponding Java classes
- **THEN** validation succeeds and reports no problems

### Requirement: Entity types

The system SHALL support entity types that are either concrete or abstract, that declare attributes, that include any number of traits, and that have at most one supertype. The supertype MUST be an entity type, and supertype chains MUST NOT form a cycle.
An entity type SHALL have the attributes it declares, the attributes of the traits it includes, and the attributes of its supertype, transitively.

#### Scenario: Abstract supertype with concrete subtypes

- **WHEN** a definition declares the abstract entity type Party including the trait Named, and the concrete entity types Person and Organization with supertype Party
- **THEN** validation succeeds
- **AND** the attributes of Person are `name` (from Named) and `birthDate`

#### Scenario: Supertype that is not an entity type

- **WHEN** an entity type declares a trait or a relationship type as its supertype
- **THEN** validation fails with a problem located at that entity type

#### Scenario: Cycle in supertypes

- **WHEN** entity type A has supertype B and entity type B has supertype A
- **THEN** validation fails with a problem that names both types

### Requirement: Traits

The system SHALL support traits that declare attributes for entity types to include. A trait MUST NOT be used as a supertype or as the source or target of a relationship type. A trait that reaches a type more than once, through the type's supertypes and the type itself, SHALL contribute its attributes once.

#### Scenario: Attributes come from a trait

- **WHEN** the trait Named declares the attribute `name` and the entity type Party includes Named
- **THEN** Party and all its subtypes have the attribute `name`

#### Scenario: Trait reached twice

- **WHEN** Party includes the trait Named and its subtype Person also includes Named
- **THEN** validation succeeds, and Person has the attribute `name` once

#### Scenario: Trait as a relationship end

- **WHEN** a relationship type declares a trait as its source or target
- **THEN** validation fails with a problem located at that relationship type

### Requirement: Attributes

Every attribute SHALL have a name and exactly one value type from: string, boolean, int (32-bit), long (64-bit), decimal, date and instant.
An attribute MAY be marked required or unique. Attributes of type int, long, decimal, date or instant MAY declare a range, with a lower and an upper bound, each optional and each inclusive or exclusive. Attributes of type string MAY declare allowed values, a pattern, a minimum length and a maximum length: a value MUST then be one of the allowed values, match the pattern as a whole, and have a length, counted in Unicode code points, within the bounds.
Attribute names MUST be unique across an entity type, its supertypes and the traits they include, and no element may declare the same name twice. A range MUST NOT be declared on a string or boolean attribute. Its bounds MUST fit the attribute's value type, where an int fits a long or decimal attribute and a long fits a decimal attribute. At least one value MUST fit the range: the lower bound is below the upper bound, or equal to it with both bounds inclusive.
Allowed values, patterns and lengths MUST NOT be declared on attributes of other value types than string. A pattern MUST be a valid I-Regexp (RFC 9485), and matching it SHALL take time linear in the length of the value. A pattern MUST also stay within the limits that keep matching safe, which the explanation of the problem code for invalid patterns states: repeat counts of at most 1000, at most 10,000 elements once repeats are expanded, and limited nesting and branching. Lengths MUST NOT be negative, and a minimum length MUST NOT exceed a maximum length. Allowed values MUST be distinct.

#### Scenario: Name declared twice in a hierarchy

- **WHEN** the entity type Organization declares the attribute `name`, and Organization's supertype Party already has `name` through the trait Named
- **THEN** validation fails with a problem that names both declarations

#### Scenario: Attribute declared twice by one type

- **WHEN** the entity type Account declares the attribute `currency` twice, with the same definition
- **THEN** validation fails with a problem located at `Account.currency`

#### Scenario: Same name from two traits

- **WHEN** an entity type includes two traits that both declare the attribute `code`
- **THEN** validation fails with a problem that names both traits

#### Scenario: Range on an unordered value type

- **WHEN** a boolean attribute declares a range
- **THEN** validation fails with a problem located at that attribute

#### Scenario: Range bound of the wrong value type

- **WHEN** a date attribute declares a range whose lower bound is the int 1
- **THEN** validation fails with a problem located at that attribute

#### Scenario: Empty range

- **WHEN** an int attribute declares a range from 10 (inclusive) to 10 (exclusive)
- **THEN** validation fails with a problem located at that attribute

#### Scenario: Valid range

- **WHEN** the relationship type Owns declares the decimal attribute `share`, required, with a lower bound of 0 (exclusive) and an upper bound of 100 (inclusive)
- **THEN** validation succeeds

#### Scenario: Valid string constraints

- **WHEN** the attribute `currency` of Account declares the pattern `[A-Z]{3}`, and the attribute `segment` of CustomerRole allows the values `Corporate` and `Retail`
- **THEN** validation succeeds

#### Scenario: Pattern on a non-string attribute

- **WHEN** the decimal attribute `balance` of Account declares a pattern
- **THEN** validation fails with a problem located at `Account.balance`

#### Scenario: Invalid pattern

- **WHEN** the attribute `currency` of Account declares the pattern `[A-Z`
- **THEN** validation fails with a problem located at `Account.currency`

#### Scenario: Pattern outside the portable syntax

- **WHEN** the attribute `currency` of Account declares the pattern `\d{3}`
- **THEN** validation fails with a problem located at `Account.currency`, whose hint suggests `[0-9]` instead of `\d`

#### Scenario: Minimum length above maximum length

- **WHEN** the attribute `segment` of CustomerRole declares a minimum length of 5 and a maximum length of 3
- **THEN** validation fails with a problem located at `CustomerRole.segment`

#### Scenario: Allowed value listed twice

- **WHEN** the attribute `segment` of CustomerRole allows the values `Corporate`, `Retail` and `Corporate`
- **THEN** validation fails with a problem located at `CustomerRole.segment`

### Requirement: Relationship types

The system SHALL support relationship types that declare a source entity type, a target entity type, an inverse name, two multiplicities, and whether they are a composition or an association. They MAY be marked symmetric, MAY declare attributes, and MAY have at most one relationship supertype.
The two multiplicities SHALL state how many targets each source can have and how many sources each target can have. Each multiplicity SHALL be a minimum of 0 or 1 and a maximum of 1 or unbounded.
Further rules:
- In a composition, each target MUST have exactly one source, and the relationship type MUST NOT be symmetric.
- A symmetric relationship type MUST have the same source and target entity type, and the same multiplicity at both ends.
- A relationship type with a supertype MUST have a source and a target that are equal to, or subtypes of, the supertype's source and target. It SHALL have the supertype's attributes as well as its own.

#### Scenario: Relationship to an abstract type

- **WHEN** the relationship type Owns declares the source Organization, the target Party, from 0 to unbounded targets per source and from 0 to unbounded sources per target, and the inverse name `ownedBy`
- **THEN** validation succeeds

#### Scenario: Exactly one target per source

- **WHEN** the relationship type HeldBy declares the source Account, the target Party, and exactly one target per source
- **THEN** validation succeeds, and the schema reports a minimum and a maximum of 1 targets per source

#### Scenario: Composition with shared targets

- **WHEN** the composition HasAddress declares from 0 to unbounded sources per target
- **THEN** validation fails with a problem located at HasAddress

#### Scenario: Symmetric relationship between different types

- **WHEN** a symmetric relationship type declares the source Person and the target Organization
- **THEN** validation fails with a problem located at that relationship type

#### Scenario: Relationship subtype with an unrelated target

- **WHEN** a relationship type has the supertype Owns and declares the target Address, which is not Party or a subtype of Party
- **THEN** validation fails with a problem located at that relationship type

### Requirement: Names

The names of entity types, traits and relationship types SHALL be unique among all type names in the schema. Relationship type names and inverse names, which name the two directions of navigation, SHALL be unique among each other. Attribute names SHALL be unique as stated in the attribute rules.
Names SHALL be compared without regard to case and kept as written. Aliases (see the `schema-evolution` capability) count as names for all of these rules.
Every name of a type, attribute or inverse direction MUST start with an ASCII letter, contain only ASCII letters, digits and underscores, and be at most 128 characters long.

#### Scenario: Duplicate type name

- **WHEN** a definition declares an entity type and a trait both named Party
- **THEN** validation fails with a problem that names both declarations

#### Scenario: Names that differ only in case

- **WHEN** a definition declares the entity type Party and the trait `party`
- **THEN** validation fails with a problem that names both declarations

#### Scenario: Inverse name equal to a relationship type name

- **WHEN** the relationship type Employs declares the inverse name `owns`, and the relationship type Owns exists
- **THEN** validation fails with a problem located at Employs

#### Scenario: Invalid characters

- **WHEN** an attribute is named `birth-date`
- **THEN** validation fails with a problem located at that attribute

### Requirement: Type hierarchy is queryable

For every entity type and relationship type in a valid schema, the system SHALL report its supertypes in order (nearest first), its subtypes (transitively), and its complete set of attributes with the type or trait that declares each one. For an entity type it SHALL also report whether it is abstract.

#### Scenario: Subtypes of an abstract type

- **WHEN** the schema of the reference domain is queried for the subtypes of Party
- **THEN** the answer is Person and Organization

#### Scenario: Declaring element of an inherited attribute

- **WHEN** the schema of the reference domain is queried for the attributes of Organization
- **THEN** the answer contains `lei`, declared by Organization, and `name`, declared by the trait Named

### Requirement: Validation reports every problem

WHEN a schema definition is invalid, THE SYSTEM SHALL report every problem it finds, not only the first. Each problem SHALL have a stable code, the path of the element it concerns (a type name, or a type name and an attribute, inverse name, rule name or check name joined by a dot) and a readable message. An invalid definition MUST NOT change the stored schema.
Where it applies, a problem SHALL also state what was expected, what was found, and a hint for fixing it. A problem about a name that refers to nothing SHALL give as its hint the closest name that exists, when one differs from it by at most two letters, compared without regard to case.

#### Scenario: Several problems at once

- **WHEN** a definition declares two types named Party and a range on the boolean attribute `active` of Account
- **THEN** validation reports two problems, each with its code, one with the path `Party` and one with the path `Account.active`
- **AND** the stored schema is unchanged

#### Scenario: Hint for a misspelled reference

- **WHEN** the supertype of Organization is written `Prty`
- **THEN** validation fails with a problem located at `Organization` whose hint names Party

#### Scenario: Expected and found values

- **WHEN** an entity type is named `1Party`
- **THEN** validation fails with a problem that states the form a name must have, and that it found `1Party`

### Requirement: Deprecation marks

Entity types, traits, relationship types and attributes MAY be marked deprecated. The system SHALL keep the mark in the schema and SHALL report it in schema queries. In this capability the mark has no other effect.

#### Scenario: Deprecated attribute is reported

- **WHEN** the attribute `birthDate` of Person is marked deprecated, and the schema is queried for the attributes of Person
- **THEN** `birthDate` is reported, marked as deprecated

### Requirement: Descriptions and examples

Entity types, traits, relationship types and attributes MAY have a description, and attributes MAY list examples. The system SHALL keep descriptions and examples in the schema and SHALL report them in schema queries.
Every example MUST fit the attribute's value type, as a range bound must, and MUST satisfy the attribute's range, allowed values, pattern and lengths.

#### Scenario: Descriptions are reported

- **WHEN** Organization is described as "A company or another legal entity", and the schema is queried for Organization
- **THEN** the description is reported

#### Scenario: Example that breaks a pattern

- **WHEN** the attribute `lei` of Organization declares the pattern `[A-Z0-9]{18}[0-9]{2}` and lists the example `ABC`
- **THEN** validation fails with a problem located at `Organization.lei`

#### Scenario: Example of the wrong value type

- **WHEN** the date attribute `birthDate` of Person lists the string `tomorrow` as an example
- **THEN** validation fails with a problem located at `Person.birthDate`

### Requirement: Rule references

Entity types and relationship types MAY name rules, each by a name and a version. A rule name SHALL consist of lowercase ASCII letters, digits and hyphens, start with a letter, and have at most 128 characters. A version SHALL be a whole number of at least 1.
Every named rule MUST be available to the schema registry in that version, for the type that names it or for one of that type's supertypes. A type MUST NOT name a rule, in any version, that it or one of its supertypes already names.
A rule named by a type SHALL also apply to the type's subtypes. The schema SHALL report, for each type, the rules it names and the rules it inherits.

#### Scenario: Naming an available rule

- **WHEN** version 1 of the rule `lei-checksum` is available for Organization, and Organization names it
- **THEN** validation succeeds

#### Scenario: Unknown rule version

- **WHEN** only version 1 of the rule `lei-checksum` is available, and Organization names version 2
- **THEN** validation fails with a problem located at `Organization.lei-checksum`, whose hint names version 1

#### Scenario: Rule for another type

- **WHEN** the rule `lei-checksum` is available for Organization, and Person names it
- **THEN** validation fails with a problem located at `Person.lei-checksum`

#### Scenario: Rule inherited from a supertype

- **WHEN** version 1 of the rule `name-not-blank` is available for Party, Party names it, and the schema is queried for the rules of Organization
- **THEN** the answer contains `name-not-blank`, inherited from Party

#### Scenario: Rule named twice in a hierarchy

- **WHEN** Party and its subtype Organization both name the rule `name-not-blank`
- **THEN** validation fails with a problem located at `Organization.name-not-blank`

#### Scenario: Two versions of a rule in a hierarchy

- **WHEN** versions 1 and 2 of the rule `name-not-blank` are available for Party, Party names version 1, and Organization names version 2
- **THEN** validation fails with a problem located at `Organization.name-not-blank`

### Requirement: Available rules are listed

THE SYSTEM SHALL list the rules available to a schema registry, each with its name, version, description, the type it applies to, and the problem codes it can report, each code with an explanation. A registry MUST NOT be given two rules with the same name and version, or a rule whose name or version breaks the rules for rule references.

#### Scenario: Listing available rules

- **WHEN** a registry is given version 1 of the rules `lei-checksum` and `total-ownership-at-most-100`, and its available rules are listed
- **THEN** both are listed with their versions, descriptions, types and problem codes

#### Scenario: Same rule given twice

- **WHEN** a registry is created with version 1 of the rule `lei-checksum` twice
- **THEN** creating the registry fails

#### Scenario: Rule with an invalid name

- **WHEN** a registry is created with a rule named `Lei_Checksum`
- **THEN** creating the registry fails

### Requirement: Problem codes are explained

THE SYSTEM SHALL give every problem code it defines an explanation that can be read at runtime. Codes that rules define are explained by the rule that defines them.

#### Scenario: Explaining a code

- **WHEN** the explanation of the code for a reference to an unknown name is read
- **THEN** it is a text that is not empty
