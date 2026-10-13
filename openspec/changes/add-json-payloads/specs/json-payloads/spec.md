# Spec Delta

## Purpose

Defines transaction payloads in YAML 1.2 or JSON, their reading with located problems, JSON forms of results, and JSON Schemas generated from a schema.

## ADDED Requirements

### Requirement: Transaction payloads

THE SYSTEM SHALL read a transaction payload, written in YAML 1.2 or as JSON, into a transaction, using a schema to type its values. A payload SHALL be a mapping with the format version 1 (`cairn: 1`), the principal, and optionally the party acted for, the reason and the idempotency key, and a list of writes. Each write SHALL be a mapping with one key naming its kind (`create`, `relate`, `patch`, `replace` or `endDate`) whose value holds its fields with the names of the transaction API. A reference SHALL be written as `$` followed by a temporary ID, or as an ID. Instants SHALL be ISO 8601 UTC strings.
Values SHALL be read by the value type of the attribute they belong to, in the entity or relationship type the write concerns, as schema documents read literals (see the `schema-documents` capability). A value whose attribute is unknown SHALL be read as a value of its own YAML type, so that the transaction's validation reports it.

#### Scenario: Creating organizations from a payload

- **WHEN** a JSON payload with the principal `agent:onboarding` creates ACME and Beta with temporary IDs `acme` and `beta`, effective from 1 January, and is read and committed
- **THEN** the transaction is committed, and its receipt maps `acme` and `beta` to IDs

#### Scenario: A date value

- **WHEN** a payload creates Alice with the birth date `"1990-05-17"`
- **THEN** the transaction holds a date value for `birthDate`

#### Scenario: Unknown write kind

- **WHEN** a payload contains a write with the key `upsert`
- **THEN** reading fails with a problem located at that write, whose hint lists the kinds of writes

### Requirement: Payload problems are located

Every problem from reading a payload SHALL be located in it by a JSON Pointer, a line and a column. For a payload that was read, THE SYSTEM SHALL locate the problems that a dry run or a commit of its transaction reports: a problem about an entity, relationship or attribute SHALL be located at the last write in the payload that concerns it, at the attribute's key where there is one.

#### Scenario: Locating a value problem

- **WHEN** a payload's second write creates account A-1 with the currency `eur`, a later write relates it to its holder, and its dry run's problems are located in the payload
- **THEN** the problem is located at `/writes/1/create/values/currency`

### Requirement: Results as JSON

THE SYSTEM SHALL write receipts, entity states, relationship states, timelines, navigation results, traversal results and problems as JSON, with the field names of the Java API, IDs as strings, instants as ISO 8601 UTC strings, dates as ISO 8601 dates, and decimals as JSON numbers that keep every digit. An entity state written as JSON SHALL be readable as the values of a `replace` write.

#### Scenario: Reading back what was written

- **WHEN** ACME's state is written as JSON, its `values` are used in a replace payload, and the payload is read
- **THEN** the replace sets exactly ACME's values

#### Scenario: Problems as JSON

- **WHEN** a commit is rejected with a problem that has a hint and a location, and the problem is written as JSON
- **THEN** the JSON has the code, path, message, expected value, found value, hint and location

### Requirement: Generated JSON Schemas

THE SYSTEM SHALL generate, from a schema, a JSON Schema (draft 2020-12) for the values of each entity type and relationship type, and one for transaction payloads. The schema of a type's values SHALL list its attributes, including inherited ones, with their value types, the required ones, ranges, allowed values, patterns, lengths, descriptions and examples, and SHALL allow no other properties. The schema for transaction payloads SHALL check the values of `create` and `relate` writes against the schema of the type they name.
Every payload that the system reads and commits without value problems, that writes type and attribute names as the schema does, and whose creates and relates give every required value themselves, SHALL be valid against the generated schema for transactions.

#### Scenario: Values of Organization

- **WHEN** the JSON Schema for the values of Organization is generated from the reference domain model
- **THEN** it requires `name`, describes `lei` as a string with the pattern of the LEI, and allows no other properties

#### Scenario: A payload that breaks the schema

- **WHEN** a payload creates an Organization with the value `nmae` and is checked against the generated JSON Schema for transactions
- **THEN** it is invalid
