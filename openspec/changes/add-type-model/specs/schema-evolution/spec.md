# Spec Delta

## Purpose

Defines how a schema definition is registered with sysRec, how the stored schema keeps its history in transaction time, and which changes to it are compatible.

## ADDED Requirements

### Requirement: Registering a schema

The system SHALL accept a schema definition together with an origin, which is a name and a version, and SHALL validate it (see the `type-model` capability).
A valid definition that changes the stored schema SHALL be applied as a schema transaction. The transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction.
The result of an applied registration SHALL contain the transaction time, the basis and the identifier of every type and attribute in the stored schema.

#### Scenario: First registration

- **WHEN** an empty store receives the reference domain model with the origin `reference-model` version `1.0.0`
- **THEN** the registration is applied as a schema transaction
- **AND** the result contains its transaction time, its basis and an identifier for every type and attribute

#### Scenario: Invalid definition

- **WHEN** a registration contains a definition with validation problems
- **THEN** the registration is rejected with every problem
- **AND** no transaction is recorded

### Requirement: Stable identifiers

The system SHALL give every entity type, trait, relationship type and attribute an identifier when it is first registered. The identifier MUST never change, including when the element is renamed, and MUST never be reused for another element.

#### Scenario: Identifiers survive later registrations

- **WHEN** the origin `reference-model` registers version `1.1.0`, which adds the optional attribute `email` to Person
- **THEN** every type and attribute registered in version `1.0.0` keeps its identifier
- **AND** `email` gets a new identifier

### Requirement: Unchanged registrations

WHEN a registration's definition is equal to the stored definition of its origin, THE SYSTEM SHALL report the registration as unchanged and SHALL NOT record a transaction.

#### Scenario: Registering the same model twice

- **WHEN** the origin `reference-model` registers version `1.0.0` again with the same definition
- **THEN** the result is "unchanged" and no transaction is recorded

### Requirement: Schema history in transaction time

The system SHALL keep every version of the stored schema and SHALL return the schema as it was at any transaction time or basis. A request for a point before the first schema transaction SHALL return an empty schema. The schema SHALL NOT have an effective time.

#### Scenario: Reading an earlier version

- **WHEN** version `1.0.0` of `reference-model` was applied at basis b1, version `1.1.0` was applied at basis b2, and the schema is requested as of b1
- **THEN** the returned schema is the one registered by version `1.0.0`, without `email`

#### Scenario: Before any registration

- **WHEN** the schema is requested as of a transaction time before the first schema transaction
- **THEN** the returned schema has no types

### Requirement: Origins own their types

Every type SHALL record the origin that registered it. A registration SHALL contain the complete set of types of its origin. It MAY reference types of other origins as supertypes, included traits, or relationship sources and targets. A registration MUST be rejected if it defines a type that another origin owns.

#### Scenario: Referencing another origin's types

- **WHEN** the origin `crm-model` registers the entity type Lead with the supertype Party, which `reference-model` owns
- **THEN** the registration is applied, and Lead records the origin `crm-model`

#### Scenario: Redefining another origin's type

- **WHEN** the origin `crm-model` registers a definition that also defines Party
- **THEN** the registration is rejected with a problem located at Party

### Requirement: Versions of an origin

A registration whose definition differs from the stored definition of its origin MUST carry a higher version than the stored one. Versions SHALL be compared as sequences of numbers separated by dots.
WHEN an origin registers a lower version than the stored one, and the stored definition is a compatible evolution of the submitted one, THE SYSTEM SHALL report the registration as unchanged and keep the stored schema. This lets an application roll back to an earlier release.

#### Scenario: Rolling back to an earlier release

- **WHEN** version `1.1.0` of `reference-model` is stored and version `1.0.0` is registered with its original definition
- **THEN** the result is "unchanged", and the stored schema still contains `email`

#### Scenario: Changed definition with the same version

- **WHEN** version `1.0.0` of `reference-model` is stored and a different definition is registered as version `1.0.0`
- **THEN** the registration is rejected with a problem that names the origin and its version

### Requirement: Compatible changes are applied

The system SHALL apply a registration whose differences from the stored definition of its origin are all compatible. These changes are compatible:
- adding an entity type or trait
- adding a relationship type, unless it requires at least one relationship for an entity type that already exists (a minimum of 1 at that end)
- adding an optional attribute, or including a trait whose attributes are all optional
- renaming a type, attribute or inverse name while declaring the previous name as an alias
- widening a value type: int to long, int to decimal, or long to decimal
- making a required attribute optional, removing a unique constraint, or widening a range
- moving an attribute to a supertype, keeping its definition
- marking an attribute or type as deprecated
- making an abstract entity type concrete
- changing a relationship type's source or target to one of its supertypes
- relaxing a multiplicity, by lowering a minimum or raising a maximum

#### Scenario: Adding an optional attribute

- **WHEN** the stored model of `reference-model` is version `1.0.0` and version `1.1.0` adds the optional attribute `email` to Person
- **THEN** the registration is applied

#### Scenario: Widening a value type

- **WHEN** a later version changes an attribute's value type from int to long
- **THEN** the registration is applied, and the attribute keeps its identifier

#### Scenario: Relaxing a multiplicity

- **WHEN** a later version changes HeldBy from exactly one target per source to from 1 to unbounded targets per source
- **THEN** the registration is applied

### Requirement: Breaking changes are rejected

The system SHALL reject a registration whose differences from the stored definition of its origin include any breaking change. The rejection SHALL list every breaking change with its path and reason, and MUST NOT change the stored schema. These changes are breaking:
- removing a type, an attribute, or a trait from a type's included traits
- renaming without declaring the previous name as an alias
- narrowing a value type, a range, a relationship source or target, or a multiplicity
- adding a required attribute to an existing type, or making an optional attribute required
- adding a relationship type that requires at least one relationship for an entity type that already exists
- adding a unique constraint to an existing attribute
- adding, removing or changing an entity type's or relationship type's supertype
- making a concrete entity type abstract
- changing a relationship type between composition and association, or between symmetric and not symmetric
- changing any other property of an existing element, unless it is listed as a compatible change

#### Scenario: Removing an attribute

- **WHEN** a later version of `reference-model` no longer declares `birthDate` on Person
- **THEN** the registration is rejected with a breaking change located at `Person.birthDate`

#### Scenario: Renaming without an alias

- **WHEN** a later version renames the attribute `lei` of Organization to `legalEntityId` without declaring `lei` as an alias
- **THEN** the registration is rejected, reporting `lei` as removed

#### Scenario: Making an attribute required

- **WHEN** a later version marks the optional attribute `birthDate` of Person as required
- **THEN** the registration is rejected with a breaking change located at `Person.birthDate`

#### Scenario: Several breaking changes

- **WHEN** a later version removes `birthDate` from Person and makes Organization abstract
- **THEN** the registration is rejected, listing both breaking changes
- **AND** the stored schema is unchanged

### Requirement: Aliases resolve to renamed elements

WHEN a type, attribute or inverse name is looked up in the schema by a name declared as its alias, THE SYSTEM SHALL return the renamed element. An alias MUST NOT be used as the name or alias of any other element of the same kind in the schema.

#### Scenario: Looking up an old name

- **WHEN** version `1.2.0` of `reference-model` renames `lei` to `legalEntityId` with the alias `lei`, and the attributes of Organization are looked up by `lei`
- **THEN** the result is the attribute `legalEntityId`, with the identifier that `lei` had
