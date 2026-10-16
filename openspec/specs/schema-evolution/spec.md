# schema-evolution Specification

## Purpose
Defines how a schema definition is registered with Cairn, how the stored schema keeps its history in transaction time, and which changes to it are compatible.

## Requirements

### Requirement: Registering a schema

The system SHALL accept a schema definition together with an origin, which is a name and a version, and SHALL validate it (see the `type-model` capability). An origin name MUST be non-empty and contain only ASCII letters, digits, dots, hyphens and underscores. A version MUST be one or more numbers separated by dots. Problems with the origin itself have the path `@origin`, which no type name can take, and are reported together with the definition's problems.
A valid definition that changes the stored schema SHALL be applied as a schema transaction. The transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction.
The result of an applied registration SHALL contain the transaction time, the basis and the identifier of every type and attribute in the stored schema.

#### Scenario: First registration

- **WHEN** an empty store receives the reference domain model with the origin `reference-model` version `1.0.0`
- **THEN** the registration is applied as a schema transaction
- **AND** the result contains its transaction time, its basis and an identifier for every type and attribute

#### Scenario: Malformed origin

- **WHEN** a registration carries the origin version `1.x`
- **THEN** the registration is rejected with a problem located at the origin

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

WHEN a registration's definition is equal to the stored definition of its origin, THE SYSTEM SHALL report the registration as unchanged and SHALL NOT record a transaction. Definitions are equal when they contain the same elements, whatever the order in which types, attributes, traits, aliases, allowed values, rule references and examples are listed. Numeric range bounds are equal when they have the same value, such as 100 and 100.0.

#### Scenario: Registering the same model twice

- **WHEN** the origin `reference-model` registers version `1.0.0` again with the same definition
- **THEN** the result is "unchanged" and no transaction is recorded

#### Scenario: Same model in a different order

- **WHEN** the origin `reference-model` registers version `1.0.0` again with the same types listed in a different order
- **THEN** the result is "unchanged" and no transaction is recorded

#### Scenario: Same allowed values in a different order

- **WHEN** the origin `reference-model` registers version `1.0.0` again with the allowed values of `segment` listed as `Retail` and `Corporate`
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

Every type SHALL record the origin that registered it, with the version of that origin's latest applied registration. A registration SHALL contain the complete set of types of its origin. It MAY reference types of other origins as supertypes, included traits, or relationship sources and targets. A registration MUST be rejected if it defines a type that another origin owns.

#### Scenario: Referencing another origin's types

- **WHEN** the origin `crm-model` registers the entity type Lead with the supertype Party, which `reference-model` owns
- **THEN** the registration is applied, and Lead records the origin `crm-model`

#### Scenario: Redefining another origin's type

- **WHEN** the origin `crm-model` registers a definition that also defines Party
- **THEN** the registration is rejected with a problem located at Party

### Requirement: Versions of an origin

Versions SHALL be compared as sequences of numbers separated by dots, where a missing number counts as 0, so `1.0` and `1.0.0` are the same version. When a registration's definition differs from the stored definition of its origin, the system SHALL handle it by version:
- a higher version SHALL be checked for compatibility, then applied or rejected as the following requirements state
- the same version MUST be rejected
- a lower version SHALL be reported as unchanged, keeping the stored schema, when the stored definition is a compatible evolution of the submitted one. This lets an application roll back to an earlier release. Otherwise it MUST be rejected.

#### Scenario: Rolling back to an earlier release

- **WHEN** version `1.1.0` of `reference-model` is stored and version `1.0.0` is registered with its original definition
- **THEN** the result is "unchanged", and the stored schema still contains `email`

#### Scenario: Lower version that is not a predecessor

- **WHEN** version `1.1.0` of `reference-model` is stored and version `1.0.5` is registered with the optional attribute `nickname` on Person, which `1.1.0` does not have
- **THEN** the registration is rejected, reporting that the stored definition removes `Person.nickname`

#### Scenario: Changed definition with the same version

- **WHEN** version `1.0.0` of `reference-model` is stored and a different definition is registered as version `1.0.0`
- **THEN** the registration is rejected with a problem that names the origin and its version

### Requirement: Compatible changes are applied

The system SHALL apply a registration whose differences from the stored definition of its origin are all compatible. These changes are compatible:
- adding an entity type or trait
- adding a relationship type, unless it requires at least one relationship (a minimum of 1 at that end) for a concrete entity type that already exists
- adding an optional attribute, or including a trait whose attributes are all optional, unless that extends an existing unique attribute to more types
- renaming a type, attribute or inverse name while declaring the previous name as an alias
- widening a value type: int to long, int to decimal, or long to decimal
- making a required attribute optional, removing a unique constraint, or widening a range
- relaxing a string constraint: adding allowed values to an attribute that already has some, removing all allowed values, removing a pattern, lowering or removing a minimum length, or raising or removing a maximum length
- adding, changing or removing a description or examples
- removing a rule from a type
- removing a check from a type, or adding, changing or removing a check's message
- making a derived attribute plain, keeping its stored values
- moving an optional attribute without a unique constraint to a supertype, keeping its definition, unless that extends a derived attribute to more existing types
- marking an attribute or type as deprecated
- making an abstract entity type concrete
- changing a relationship type's source or target to one of its supertypes, unless that end requires a relationship (a minimum of 1) and the change extends it to concrete entity types that already exist
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

#### Scenario: Changing a description

- **WHEN** a later version changes the description of Organization
- **THEN** the registration is applied

#### Scenario: Adding an allowed value

- **WHEN** the attribute `segment` of CustomerRole allows `Corporate` and `Retail`, and a later version also allows `Private`
- **THEN** the registration is applied

#### Scenario: Removing a rule

- **WHEN** a later version of `reference-model` no longer names the rule `lei-checksum` on Organization
- **THEN** the registration is applied

#### Scenario: Removing a check

- **WHEN** the stored model declares the check `balance-not-negative` on Account, and a later version of the origin no longer declares it
- **THEN** the registration is applied

#### Scenario: Making a derived attribute plain

- **WHEN** the stored model declares the derived attribute `label` on Account, and a later version of the origin declares `label` without `derived`
- **THEN** the registration is applied

### Requirement: Breaking changes are rejected

The system SHALL reject a registration whose differences from the stored definition of its origin include any breaking change. The rejection SHALL list every breaking change with its path and reason, and a hint for fixing it where one applies, and MUST NOT change the stored schema. In these rules, an existing type is a concrete entity type or a relationship type that the stored schema already has: abstract types have no entities of their own. These changes are breaking:
- removing a type, an attribute, or a trait from a type's included traits
- renaming without declaring the previous name as an alias
- narrowing a value type, a range, a relationship source or target, or a multiplicity
- adding a required attribute to an existing type, or making an optional attribute required
- adding a relationship type that requires at least one relationship for a concrete entity type that already exists
- adding a unique constraint to an existing attribute
- tightening a string constraint: giving allowed values, a pattern or a length to an existing attribute that had none, removing an allowed value, changing a pattern, raising a minimum length, or lowering a maximum length
- adding a rule to an entity type or relationship type that the stored schema already has, abstract or not, or changing the version of a rule it names
- adding a check to an entity type or relationship type that the stored schema already has, abstract or not, or changing the expression of a check it declares
- adding a derived attribute to a type that the stored schema already has, making an attribute derived, or changing the expression of a derived attribute
- extending an existing required, unique or derived attribute to more existing types, for example by moving it to a supertype or by including its trait in another type
- widening a relationship source or target that requires a relationship (a minimum of 1) to concrete entity types that already exist
- adding, removing or changing an entity type's or relationship type's supertype
- making a concrete entity type abstract
- changing a relationship type between composition and association, or between symmetric and not symmetric
- changing any other property of an existing element, unless it is listed as a compatible change

#### Scenario: Removing an attribute

- **WHEN** a later version of `reference-model` no longer declares `birthDate` on Person
- **THEN** the registration is rejected with a breaking change located at `Person.birthDate`
- **AND** its hint suggests marking the attribute deprecated instead, or keeping its name as an alias if it was renamed

#### Scenario: Renaming without an alias

- **WHEN** a later version renames the attribute `lei` of Organization to `legalEntityId` without declaring `lei` as an alias
- **THEN** the registration is rejected, reporting `lei` as removed

#### Scenario: Making an attribute required

- **WHEN** a later version marks the optional attribute `birthDate` of Person as required
- **THEN** the registration is rejected with a breaking change located at `Person.birthDate`

#### Scenario: Including a trait with a unique attribute

- **WHEN** version `1.0.0` also defines the trait Coded with the optional unique attribute `code`, included by Account, and a later version also includes Coded in CustomerRole
- **THEN** the registration is rejected with a breaking change located at `Coded.code`

#### Scenario: Widening a required relationship end

- **WHEN** version `1.0.0` also defines the relationship type Manages from Person to Organization, with exactly one target per source, and a later version changes its source from Person to Party
- **THEN** the registration is rejected with a breaking change located at Manages, because every existing Organization would now need a target

#### Scenario: Removing an allowed value

- **WHEN** the attribute `segment` of CustomerRole allows `Corporate` and `Retail`, and a later version allows only `Corporate`
- **THEN** the registration is rejected with a breaking change located at `CustomerRole.segment`

#### Scenario: Adding a rule to an existing type

- **WHEN** version 1 of the rule `birth-date-in-past` is available for Person, and a later version of `reference-model` names it on Person
- **THEN** the registration is rejected with a breaking change located at `Person.birth-date-in-past`

#### Scenario: Several breaking changes

- **WHEN** a later version removes `birthDate` from Person and makes Organization abstract
- **THEN** the registration is rejected, listing both breaking changes
- **AND** the stored schema is unchanged

#### Scenario: Adding a check to an existing type

- **WHEN** the stored model has Account without checks, and a later version of the origin declares the check `balance-not-negative` on Account
- **THEN** the registration is rejected with a breaking change at `Account.balance-not-negative`

#### Scenario: Adding a derived attribute to an existing type

- **WHEN** the stored model has Account without `label`, and a later version of the origin declares the derived attribute `label` on Account
- **THEN** the registration is rejected with a breaking change at `Account.label`

### Requirement: Aliases resolve to renamed elements

WHEN a type, attribute or inverse name is looked up in the schema by a name declared as its alias, THE SYSTEM SHALL return the renamed element. Aliases SHALL follow the same uniqueness rules as names (see the `type-model` capability).

#### Scenario: Looking up an old name

- **WHEN** version `1.2.0` of `reference-model` renames `lei` to `legalEntityId` with the alias `lei`, and the attributes of Organization are looked up by `lei`
- **THEN** the result is the attribute `legalEntityId`, with the identifier that `lei` had

### Requirement: Planning a registration

THE SYSTEM SHALL plan a registration without applying it. A plan SHALL state whether the registration would be applied, leave the schema unchanged, or be rejected. It SHALL list every difference from the stored definition of the origin, each with its path, a description and whether it is compatible, and every problem that registering would report.
Planning MUST NOT record a schema transaction or change the stored schema.
For an origin's first registration, every type it defines SHALL be listed as a compatible difference. For an invalid definition, the plan SHALL list the problems and no differences. For a lower version that the stored definition evolved from compatibly, the plan SHALL say the schema would be unchanged, and SHALL list the differences from the submitted version to the stored one, which are what the stored version adds. For a changed definition with the same version as the stored one, the plan SHALL say the registration would be rejected, with the problem that the version did not increase, and SHALL still list the differences.

#### Scenario: Plan of a compatible change

- **WHEN** version `1.0.0` of `reference-model` is stored, and a registration of version `1.1.0` that adds the optional attribute `email` to Person is planned
- **THEN** the plan says the registration would be applied, and lists a compatible change located at `Person.email`
- **AND** no schema transaction is recorded

#### Scenario: Plan of a breaking change

- **WHEN** version `1.0.0` of `reference-model` is stored, and a registration of a later version that no longer declares `birthDate` on Person is planned
- **THEN** the plan says the registration would be rejected, lists a breaking change located at `Person.birthDate`, and lists the problem that registering would report

#### Scenario: Plan of an unchanged registration

- **WHEN** version `1.0.0` of `reference-model` is stored, and registering the same version again is planned
- **THEN** the plan says the schema would be unchanged, and lists no differences

#### Scenario: Plan of a first registration

- **WHEN** an empty store plans the registration of the reference domain model
- **THEN** the plan says the registration would be applied, and lists every type of the model as a compatible difference

#### Scenario: Plan of an invalid definition

- **WHEN** a registration whose definition declares two types named Party is planned
- **THEN** the plan says the registration would be rejected, lists the problem, and lists no differences

#### Scenario: Plan of a rollback

- **WHEN** versions `1.0.0` and `1.1.0` of `reference-model` were registered, where `1.1.0` adds the optional attribute `email` to Person, and registering `1.0.0` again is planned
- **THEN** the plan says the schema would be unchanged, and lists a compatible difference located at `Person.email`

#### Scenario: Plan of a changed definition with the same version

- **WHEN** version `1.0.0` of `reference-model` is stored, and a registration of version `1.0.0` that adds the optional attribute `email` to Person is planned
- **THEN** the plan says the registration would be rejected with the problem that the version did not increase, and lists a compatible difference located at `Person.email`

#### Scenario: Plan of an attribute added to a trait

- **WHEN** version `1.0.0` of `reference-model` is stored, and a registration of version `1.1.0` that adds the optional attribute `nickname` to the trait Named is planned
- **THEN** the plan lists a compatible difference located at `Named.nickname`

### Requirement: Stored definitions can be read

THE SYSTEM SHALL return, for an origin name, the origin and the schema definition that the stored schema holds for it: the definition of the latest applied registration of that origin, with its version. Origin names SHALL be matched exactly as written. For an origin that has no applied registration, it SHALL return nothing.

#### Scenario: Reading a stored definition

- **WHEN** versions `1.0.0` and `1.1.0` of `reference-model` were registered, and the stored definition of `reference-model` is read
- **THEN** the result is the origin `reference-model` version `1.1.0` and the definition registered with it

#### Scenario: Unknown origin

- **WHEN** the stored definition of an origin that has no applied registration is read
- **THEN** the result is empty

### Requirement: A store's schema registry

THE SYSTEM SHALL give every store its own schema registry. Its schema transactions SHALL take their transaction times and bases from the store's sequence, which data transactions share, so that a basis identifies one snapshot of both the schema and the data.

#### Scenario: Schema and data at one basis

- **WHEN** a store registers the reference domain model, commits RD-1, and then registers a version that adds the optional attribute `email` to Person
- **THEN** a view at the basis of RD-1 reads its entities with the schema that has no `email`

### Requirement: Who and why of schema changes

A registration MAY name the principal that submits it, the party it acts for and a reason. THE SYSTEM SHALL record them with the schema transaction and SHALL list them in the schema's history. A registration that names no principal SHALL be recorded with the principal `@unknown`.

#### Scenario: An agent's schema change in the history

- **WHEN** the principal `agent:modeler` registers a version of `reference-model` that adds the optional attribute `email` to Person, with the reason "Contact data"
- **THEN** the schema's history lists that schema transaction with the principal `agent:modeler` and the reason "Contact data"
