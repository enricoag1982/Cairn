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
- moving an optional attribute without a unique constraint to a supertype, keeping its definition, unless that extends a derived attribute to more existing types
- marking an attribute or type as deprecated
- making an abstract entity type concrete
- changing a relationship type's source or target to one of its supertypes, unless that end requires a relationship (a minimum of 1) and the change extends it to concrete entity types that already exist
- relaxing a multiplicity, by lowering a minimum or raising a maximum
- declaring that an existing relationship type's relationships outlive their targets
- adding, changing or removing an attribute's classification

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

#### Scenario: Letting relationships outlive their targets

- **WHEN** the stored model of `reference-model` has the test-only association OpenedBy, and a later version declares that OpenedBy outlives its targets
- **THEN** the registration is applied

#### Scenario: Classifying an existing attribute

- **WHEN** the stored model of `reference-model` is version `1.0.0`, and a later version classifies Account's `balance` as `finance`
- **THEN** the registration is applied
