## ADDED Requirements

### Requirement: Stored definitions can be read

THE SYSTEM SHALL return, for an origin name, the origin and the schema definition that the stored schema holds for it: the definition of the latest applied registration of that origin, with its version. Origin names SHALL be matched exactly as written. For an origin that has no applied registration, it SHALL return nothing.

#### Scenario: Reading a stored definition

- **WHEN** versions `1.0.0` and `1.1.0` of `reference-model` were registered, and the stored definition of `reference-model` is read
- **THEN** the result is the origin `reference-model` version `1.1.0` and the definition registered with it

#### Scenario: Unknown origin

- **WHEN** the stored definition of an origin that has no applied registration is read
- **THEN** the result is empty
