## MODIFIED Requirements

### Requirement: Schema document format

THE SYSTEM SHALL read a schema document written in YAML 1.2 into an origin and a schema definition. Because JSON is valid YAML 1.2, a document written as JSON SHALL be read the same way.
A document SHALL be a mapping with the key `cairn`, whose value is the format version 1, and the key `origin`, holding the origin's name and version. It MAY have the keys `traits`, `entityTypes` and `relationshipTypes`, each a mapping from type names to type definitions, and the key `$schema`, which names the published JSON Schema for editors and is otherwise ignored.
A type definition SHALL use the names of the schema definition's properties, except `abstract` for whether an entity type is abstract and `type` for an attribute's value type. A property that is left out SHALL take its default: not required, not unique, not abstract, an association, not symmetric, not deprecated, and no supertype, traits, attributes, aliases, inverse aliases, constraints, examples, description or rules. A relationship type MUST state its source, target, inverse name and both multiplicities; an attribute MUST state its value type; a rule reference MUST state its name and version. A multiplicity SHALL be written as `0..1`, `1..1`, `0..*` or `1..*`.
Numbers SHALL be written as JSON numbers: forms that only YAML knows, such as `0x10`, `0o17` or `+1`, are values of the wrong kind. Literals SHALL be written as numbers for int, long and decimal values, as `true` or `false` for boolean values, and as strings for string, date (ISO 8601 date) and instant (ISO 8601 instant, whose offset, if not UTC, is converted to UTC) values. A range SHALL be written as a mapping with at most one of `atLeast` and `greaterThan`, and at most one of `atMost` and `lessThan`.

#### Scenario: Reading the reference model

- **WHEN** the reference domain's schema document is read
- **THEN** the result is the origin `reference-model` and a schema definition equal to the reference domain model

#### Scenario: Reading a document written as JSON

- **WHEN** the reference domain's schema document is written as JSON and that text is read
- **THEN** the result equals the result of reading the YAML document

#### Scenario: Unsupported format version

- **WHEN** a document declares `cairn: 2`
- **THEN** reading fails with a problem located at the key `cairn`

#### Scenario: Range bounds

- **WHEN** the attribute `share` of Owns is written with the range `{ greaterThan: 0, atMost: 100 }`
- **THEN** the definition has a range from 0 (exclusive) to 100 (inclusive)
