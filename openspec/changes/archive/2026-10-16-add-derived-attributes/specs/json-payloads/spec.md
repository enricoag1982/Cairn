## MODIFIED Requirements

### Requirement: Generated JSON Schemas

THE SYSTEM SHALL generate, from a schema, a JSON Schema (draft 2020-12) for the values of each entity type and relationship type, and one for transaction payloads. The schema of a type's values SHALL list its attributes, including inherited ones, by their names, with their value types, the required ones, ranges, allowed values, patterns, lengths, descriptions and examples, and SHALL allow no other properties; it SHALL mark derived attributes as read-only. Dates and instants SHALL be checked by pattern as well as by format, because JSON Schema validators need not check formats. The schema for transaction payloads SHALL check the values of `create` and `relate` writes against the attributes of the type they name that are not derived, SHALL NOT accept derived attributes in the values of any write, and SHALL require `values` where that type has required attributes that are not derived.
Every payload that the system reads and commits without value problems, that names types by their names or aliases as the schema writes them and attributes by their names, and whose creates and relates give every required value themselves, SHALL be valid against the generated schema for transactions.

#### Scenario: Values of Organization

- **WHEN** the JSON Schema for the values of Organization is generated from the reference domain model
- **THEN** it requires `name`, describes `lei` as a string with the pattern of the LEI, and allows no other properties

#### Scenario: A payload that breaks the schema

- **WHEN** a payload creates an Organization with the value `nmae` and is checked against the generated JSON Schema for transactions
- **THEN** it is invalid
