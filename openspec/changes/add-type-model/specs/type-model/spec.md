# Spec Delta

## Purpose

Defines what a sysRec schema can contain (entity types, traits, attributes and relationship types) and how a schema definition is validated before it is registered.

## ADDED Requirements

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

The system SHALL support traits that declare attributes for entity types to include. A trait MUST NOT be used as a supertype or as the source or target of a relationship type.

#### Scenario: Attributes come from a trait

- **WHEN** the trait Named declares the attribute `name` and the entity type Party includes Named
- **THEN** Party and all its subtypes have the attribute `name`

#### Scenario: Trait as a relationship end

- **WHEN** a relationship type declares a trait as its source or target
- **THEN** validation fails with a problem located at that relationship type

### Requirement: Attributes

Every attribute SHALL have a name and exactly one value type from: string, boolean, int (32-bit), long (64-bit), decimal, date and instant.
An attribute MAY be marked required, unique or deprecated. Attributes of type int, long, decimal, date or instant MAY declare a range, with a lower and an upper bound, each optional and each inclusive or exclusive.
Attribute names MUST be unique across an entity type, its supertypes and the traits they include. A range MUST NOT be declared on a string or boolean attribute, and its lower bound MUST NOT exceed its upper bound.

#### Scenario: Name declared twice in a hierarchy

- **WHEN** the entity type Organization declares the attribute `name`, and Organization's supertype Party already has `name` through the trait Named
- **THEN** validation fails with a problem that names both declarations

#### Scenario: Same name from two traits

- **WHEN** an entity type includes two traits that both declare the attribute `code`
- **THEN** validation fails with a problem that names both traits

#### Scenario: Range on an unordered value type

- **WHEN** a boolean attribute declares a range
- **THEN** validation fails with a problem located at that attribute

#### Scenario: Valid range

- **WHEN** the relationship type Owns declares the decimal attribute `share`, required, with a lower bound of 0 (exclusive) and an upper bound of 100 (inclusive)
- **THEN** validation succeeds

### Requirement: Relationship types

The system SHALL support relationship types that declare a source entity type, a target entity type, a multiplicity at each end, an inverse name, and whether they are a composition or an association. They MAY be marked symmetric, MAY declare attributes, and MAY have at most one relationship supertype.
A multiplicity SHALL state how many targets each source can have and how many sources each target can have, each as a minimum of 0 or 1 and a maximum of 1 or unbounded.
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

Entity types, traits and relationship types SHALL each have a name that is unique within the schema. Inverse names SHALL be unique within the schema and SHALL differ from every type name. Aliases (see the `schema-evolution` capability) count as names for these rules.
Every name MUST start with a letter, contain only ASCII letters, digits and underscores, and be at most 128 characters long.

#### Scenario: Duplicate type name

- **WHEN** a definition declares an entity type and a trait both named Party
- **THEN** validation fails with a problem that names both declarations

#### Scenario: Inverse name equal to a type name

- **WHEN** a relationship type declares the inverse name `Person` and an entity type Person exists
- **THEN** validation fails with a problem located at that relationship type

#### Scenario: Invalid characters

- **WHEN** an attribute is named `birth-date`
- **THEN** validation fails with a problem located at that attribute

### Requirement: Type hierarchy is queryable

For every entity type and relationship type in a valid schema, the system SHALL report its supertypes in order (nearest first), its subtypes (transitively), whether it is abstract, and its complete set of attributes with the type or trait that declares each one.

#### Scenario: Subtypes of an abstract type

- **WHEN** the schema of the reference domain is queried for the subtypes of Party
- **THEN** the answer is Person and Organization

#### Scenario: Origin of an inherited attribute

- **WHEN** the schema of the reference domain is queried for the attributes of Organization
- **THEN** the answer contains `lei`, declared by Organization, and `name`, declared by the trait Named

### Requirement: Validation reports every problem

WHEN a schema definition is invalid, THE SYSTEM SHALL report every problem it finds, not only the first. Each problem SHALL have a stable code, the path of the element it concerns (for example `Organization.lei`) and a readable message. An invalid definition MUST NOT change the stored schema.

#### Scenario: Several problems at once

- **WHEN** a definition contains a duplicate type name and a range on a boolean attribute
- **THEN** validation reports two problems, each with its code and path
- **AND** the stored schema is unchanged
