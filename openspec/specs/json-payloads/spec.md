# json-payloads Specification

## Purpose
Defines transaction payloads in YAML 1.2 or JSON, their reading with located problems, JSON forms of results, and JSON Schemas generated from a schema.

## Requirements

### Requirement: Transaction payloads

THE SYSTEM SHALL read a transaction payload, written in YAML 1.2 or as JSON, into a transaction, using a schema to type its values. A payload SHALL be a mapping with the format version 1 (`cairn: 1`), the principal and a list of writes, and optionally the party acted for (`onBehalfOf`), the reason, the idempotency key and a `$schema` key, which is ignored. Each write SHALL be a mapping with one key naming its kind (`create`, `relate`, `patch`, `replace` or `endDate`) whose value holds its fields with the names of the transaction API, except that a period is written as `from` and `until` and a conflict check as `conflictCheck`. A create SHALL state `entityType` and `from`; a relate `relationshipType`, `source`, `target` and `from`; a patch and an end-date `target` and `from`; a replace `target`, `from` and `values`, so that a forgotten `values` cannot wipe a state. A `temporaryId` MAY be written with or without its `$`.
A reference SHALL be written as `$` followed by a temporary ID, as an ID, or, for an entity, as a unique key (see the `transactions` capability): a mapping with the entity type (`entityType`) and `key`, a mapping with exactly one entry, the name of a unique attribute and its value, such as `{ entityType: Account, key: { number: A-1 } }`. IDs therefore cannot start with `$`. The value of a key SHALL be read by the value type of its attribute in the named type; a key whose type or attribute the schema does not have SHALL be read with the value's own YAML type, so that the transaction's validation reports it. An ID that the payload does not create SHALL be resolved against what the store holds, at any effective time: whether it names an entity or a relationship, and its type. Without access to a store, an ID SHALL be read as an entity ID and the values of writes to it by their own YAML type. Instants SHALL be ISO 8601 strings; an offset other than UTC SHALL be converted to UTC.
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

#### Scenario: Patching a stored relationship by its ID

- **WHEN** ACME owns 60% of Beta through an Owns relationship, and a payload patches that relationship, by its ID, with the share `55` from 1 February
- **THEN** the transaction's patch targets the relationship, with a decimal value for `share`

#### Scenario: A replace without values

- **WHEN** a payload replaces ACME's state without a `values` key
- **THEN** reading fails with a problem located at that write

#### Scenario: Patching an account by its number in a payload

- **WHEN** a payload patches `{ entityType: Account, key: { number: A-1 } }` to the balance `1500` from 15 March, and is read and committed
- **THEN** the transaction's patch targets the unique key with a string value for `number` and a decimal value for `balance`, and A-1's balance effective 31 March is 1,500

#### Scenario: A key with two attributes

- **WHEN** a payload's reference is `{ entityType: Account, key: { number: A-1, currency: EUR } }`
- **THEN** reading fails with a problem located at that `key`

### Requirement: Payload problems are located

Every problem from reading a payload SHALL be located in it by a JSON Pointer, a line and a column. For a payload that was read, THE SYSTEM SHALL locate the problems that a dry run or a commit of its transaction reports: a problem about the transaction as a whole SHALL be located at the payload's root, or at the top-level key it concerns; a problem about an entity, relationship or attribute SHALL be located at the last write in the payload that concerns it, at the attribute's key where there is one; and a problem about something no write names SHALL be located at the last write concerning a subject it names, or else at the payload's root. A write that names an entity by a unique key SHALL concern that entity when the key names one, at the start of the write's period, in the store the payload is read against; then the write's values SHALL also be read by the type of that entity. Without a store, or when the key names no entity then, the write concerns no stored entity, and its values are read by the key's entity type. The problems of invalid keys and of keys that name no entity SHALL be located at the write.

#### Scenario: Locating a value problem

- **WHEN** a payload's second write creates account A-1 with the currency `eur`, a later write relates it to its holder, and its dry run's problems are located in the payload
- **THEN** the problem is located at `/writes/1/create/values/currency`

#### Scenario: Locating a problem about an entity named by a key

- **WHEN** A-1 exists (RD-5), a payload's first write patches `{ entityType: Account, key: { number: A-1 } }` with the currency `eur`, and its dry run's problems are located in the payload
- **THEN** the problem is located at `/writes/0/patch/set/currency`

### Requirement: Results as JSON

THE SYSTEM SHALL write receipts, entity states, relationship states, timelines, navigation results, traversal results and problems as JSON, with the field names of the Java API, IDs as strings, instants as ISO 8601 UTC strings, dates as ISO 8601 dates, and decimals as JSON numbers that keep every digit. Fields without a value and empty lists SHALL be left out, except the `values` of a state, which SHALL always be written. In a receipt, where an ID may name an entity or a relationship, each ID SHALL be written as `{ "entity": "<id>" }` or `{ "relationship": "<id>" }`, and temporary IDs as keys with their `$`. The `values` of an entity or relationship state written as JSON SHALL be readable as the values of a `replace` write. A receipt's key resolutions SHALL be written as `keys`, a list of `{ write, key, entity }`, with each key in the form that payloads use for references, so that an agent can use it again in a payload.

#### Scenario: Reading back what was written

- **WHEN** ACME's state is written as JSON, its `values` are used in a replace payload, and the payload is read
- **THEN** the replace sets exactly ACME's values

#### Scenario: A receipt as JSON

- **WHEN** a payload creates ACME with the temporary ID `acme` and is committed, and the receipt is written as JSON
- **THEN** its `temporaryIds` map `$acme` to `{ "entity": "<ACME's ID>" }`, and `created` lists the same

#### Scenario: Problems as JSON

- **WHEN** a commit is rejected with a problem that has a hint and a location, and the problem is written as JSON
- **THEN** the JSON has the code, path, message, expected value, found value, hint and location

#### Scenario: Key resolutions in a receipt as JSON

- **WHEN** a payload patches `{ entityType: Account, key: { number: A-1 } }` and is committed, and the receipt is written as JSON
- **THEN** its `keys` hold one entry with `write` 0, the key `{ "entityType": "Account", "key": { "number": "A-1" } }` and A-1's ID as `entity`

### Requirement: Generated JSON Schemas

THE SYSTEM SHALL generate, from a schema, a JSON Schema (draft 2020-12) for the values of each entity type and relationship type, and one for transaction payloads. The schema of a type's values SHALL list its attributes, including inherited ones, by their names, with their value types, the required ones, ranges, allowed values, patterns, lengths, descriptions and examples, and SHALL allow no other properties. Dates and instants SHALL be checked by pattern as well as by format, because JSON Schema validators need not check formats. The schema for transaction payloads SHALL check the values of `create` and `relate` writes against the schema of the type they name, and SHALL require `values` where that type has required attributes.
Every payload that the system reads and commits without value problems, that names types by their names or aliases as the schema writes them and attributes by their names, and whose creates and relates give every required value themselves, SHALL be valid against the generated schema for transactions.

#### Scenario: Values of Organization

- **WHEN** the JSON Schema for the values of Organization is generated from the reference domain model
- **THEN** it requires `name`, describes `lei` as a string with the pattern of the LEI, and allows no other properties

#### Scenario: A payload that breaks the schema

- **WHEN** a payload creates an Organization with the value `nmae` and is checked against the generated JSON Schema for transactions
- **THEN** it is invalid
