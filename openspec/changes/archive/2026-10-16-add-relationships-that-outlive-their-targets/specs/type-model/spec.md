## MODIFIED Requirements

### Requirement: Relationship types

The system SHALL support relationship types that declare a source entity type, a target entity type, an inverse name, two multiplicities, and whether they are a composition or an association. They MAY be marked symmetric, MAY declare that their relationships outlive their targets, MAY declare attributes, and MAY have at most one relationship supertype.
The two multiplicities SHALL state how many targets each source can have and how many sources each target can have. Each multiplicity SHALL be a minimum of 0 or 1 and a maximum of 1 or unbounded.
Further rules:
- In a composition, each target MUST have exactly one source, and the relationship type MUST NOT be symmetric.
- A symmetric relationship type MUST have the same source and target entity type, and the same multiplicity at both ends.
- A relationship type that outlives its targets (see the `relationships` capability) MUST be an association and MUST NOT be symmetric. It does not pass this to its subtypes.
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

#### Scenario: A relationship type that outlives its targets

- **WHEN** the test-only association OpenedBy declares the source Account, the target Person, from 0 to 1 targets per source and from 0 to unbounded sources per target, the inverse name `opened`, and that its relationships outlive their targets
- **THEN** validation succeeds, and the schema reports that OpenedBy outlives its targets

#### Scenario: A composition that outlives its targets

- **WHEN** the composition HasAddress declares that its relationships outlive their targets
- **THEN** validation fails with a problem located at HasAddress
