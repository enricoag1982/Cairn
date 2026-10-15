## MODIFIED Requirements

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
