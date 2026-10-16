## MODIFIED Requirements

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
- moving an optional attribute without a unique constraint that is not derived to a supertype, keeping its definition
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

