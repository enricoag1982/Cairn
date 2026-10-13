# Spec Delta

## Purpose

Defines the schema document format (YAML 1.2, which includes JSON): strict reading into an origin and a schema definition, problems located in the document, writing, and the published JSON Schema.

## ADDED Requirements

### Requirement: Schema document format

THE SYSTEM SHALL read a schema document written in YAML 1.2 into an origin and a schema definition. Because JSON is valid YAML 1.2, a document written as JSON SHALL be read the same way.
A document SHALL be a mapping with the key `cairn`, whose value is the format version 1, and the key `origin`, holding the origin's name and version. It MAY have the keys `traits`, `entityTypes` and `relationshipTypes`, each a mapping from type names to type definitions, and the key `$schema`, which names the published JSON Schema for editors and is otherwise ignored.
A type definition SHALL use the names of the schema definition's properties, except `abstract` for whether an entity type is abstract and `type` for an attribute's value type. A property that is left out SHALL take its default: not required, not unique, not abstract, an association, not symmetric, not deprecated, and no supertype, traits, attributes, aliases, inverse aliases, constraints, examples, description or rules. A relationship type MUST state its source, target, inverse name and both multiplicities; an attribute MUST state its value type; a rule reference MUST state its name and version. A multiplicity SHALL be written as `0..1`, `1..1`, `0..*` or `1..*`.
Numbers SHALL be written as JSON numbers: forms that only YAML knows, such as `0x10`, `0o17` or `+1`, are values of the wrong kind. Literals SHALL be written as numbers for int, long and decimal values, as `true` or `false` for boolean values, and as strings for string, date (ISO 8601 date) and instant (ISO 8601 UTC instant) values. A range SHALL be written as a mapping with at most one of `atLeast` and `greaterThan`, and at most one of `atMost` and `lessThan`.

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

### Requirement: Strict reading

WHEN a document has problems, THE SYSTEM SHALL report every problem it finds and SHALL NOT return a definition. These are problems: an unknown key, a missing key that is required, a value of the wrong kind, a multiplicity or range written in another form, and YAML features outside the supported subset: anchors, aliases, explicit tags, merge keys, duplicate keys and more than one document in a stream. A key whose value is a string, such as a version, a description or a pattern, MUST be given a string: a plain number, boolean or null there is a value of the wrong kind, so `version: 1.10` must be quoted. An unknown key SHALL come with the closest known key as a hint, when one differs from it by at most two letters.
A literal of the wrong value type, such as a string example for a date attribute, is not a problem of the document; it SHALL be read as a literal of its own type and reported by validation (see the `type-model` capability).
A YAML syntax error, and a format version other than 1, SHALL stop reading and SHALL be reported with their location.

#### Scenario: Misspelled key

- **WHEN** an attribute is written with the key `requird: true`
- **THEN** reading fails with a problem whose hint names the key `required`

#### Scenario: Anchors are rejected

- **WHEN** a document defines an anchor and refers to it with an alias
- **THEN** reading fails with a problem located at the anchor

#### Scenario: Duplicate key

- **WHEN** an entity type is defined twice under the same key in `entityTypes`
- **THEN** reading fails with a problem located at the second definition

#### Scenario: Syntax error

- **WHEN** a document contains a line with unbalanced brackets
- **THEN** reading fails with one problem that has a line and a column

### Requirement: Problems are located in the document

Every problem about a document SHALL carry its location in the document: a JSON Pointer, a line and a column, both counted from 1. Its path SHALL be the path of the element it is in, `@origin` inside the origin, or `@document` outside every element. For a document that was read, THE SYSTEM SHALL locate the problems that validating, planning or registering its definition reports: a problem about a type, attribute, inverse name or rule SHALL be located at that element, a problem about the origin at the key `origin`, and a problem whose path names no element of the document at the document's root.

#### Scenario: Locating a registration problem

- **WHEN** a document whose entity type Organization names the supertype `Prty` is read, registered, and the registration's problems are located in the document
- **THEN** the problem about Organization is located at the JSON Pointer `/entityTypes/Organization`, with the line and column where Organization is defined

#### Scenario: Problem outside every element

- **WHEN** a document has the top-level key `entityType` instead of `entityTypes`
- **THEN** reading fails with a problem whose path is `@document`, located at the key, whose hint names `entityTypes`

#### Scenario: Locating an attribute problem

- **WHEN** a document declares a pattern on the decimal attribute `balance` of Account, and validation's problems are located in the document
- **THEN** the problem is located at the JSON Pointer `/entityTypes/Account/attributes/balance`

### Requirement: Writing schema documents

THE SYSTEM SHALL write an origin and a schema definition as a schema document, either in YAML block style or as JSON, leaving out every property that has its default. The order of types and attributes SHALL follow the definition. Reading a written document SHALL give back an equal origin and definition, except that a number written for an attribute of a wider value type, such as an int bound of a decimal attribute, SHALL come back in the attribute's value type.

#### Scenario: Round trip of the reference model

- **WHEN** the reference domain model is written as a YAML document and as a JSON document, and both are read back
- **THEN** both results equal the original origin and definition

#### Scenario: Widened numbers come back in the attribute's type

- **WHEN** a definition whose decimal attribute has a range with the int bound 0 is written and read back
- **THEN** the range has the decimal bound 0, and the rest of the definition is equal

#### Scenario: Defaults are left out

- **WHEN** a definition with an optional, non-unique attribute without constraints is written
- **THEN** the attribute is written with its value type only

### Requirement: Published JSON Schema

THE SYSTEM SHALL publish a JSON Schema (draft 2020-12) that describes schema documents of format version 1. Every document that the system reads without problems SHALL be valid against it, and a document with an unknown key, a missing required key or a value of the wrong kind SHALL be invalid against it. The one exception follows from JSON Schema itself: a number with a zero fraction, such as `1.0`, counts as an integer there, while the reader rejects it where an integer is expected.

#### Scenario: The reference document is valid

- **WHEN** the reference domain's schema document is checked against the published JSON Schema
- **THEN** it is valid

#### Scenario: A misspelled key is invalid

- **WHEN** a document with the attribute key `requird` is checked against the published JSON Schema
- **THEN** it is invalid
