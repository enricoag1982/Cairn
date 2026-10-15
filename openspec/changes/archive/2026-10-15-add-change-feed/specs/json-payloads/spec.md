## MODIFIED Requirements

### Requirement: Results as JSON

THE SYSTEM SHALL write receipts, entity states, relationship states, timelines, navigation results, traversal results and problems as JSON, with the field names of the Java API, IDs as strings, instants as ISO 8601 UTC strings, dates as ISO 8601 dates, and decimals as JSON numbers that keep every digit. Fields without a value and empty lists SHALL be left out, except the `values` of a state, which SHALL always be written. In a receipt, where an ID may name an entity or a relationship, each ID SHALL be written as `{ "entity": "<id>" }` or `{ "relationship": "<id>" }`, and temporary IDs as keys with their `$`. The `values` of an entity or relationship state written as JSON SHALL be readable as the values of a `replace` write. A receipt's key resolutions SHALL be written as `keys`, a list of `{ write, key, entity }`, with each key in the form that payloads use for references, so that an agent can use it again in a payload. A change of the change feed SHALL be written with its `basis`, `transactionTime` and `kind` (`transaction`, `schema` or `purge`), its record's `principal`, `onBehalfOf` and `reason`, for a schema registration its `origin` with `name` and `version`, for a purge the numbers of `entities` and `relationships` it removed, and for a transaction `changed`, a list of `{ entity | relationship, type, created, periods }`, with each period as `{ from, until }`; `created` SHALL always be written.

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

#### Scenario: A change as JSON

- **WHEN** the transactions of RD-4 are committed, and the change of the transaction is written as JSON
- **THEN** it has the transaction's basis and transaction time, the kind `transaction`, its principal, and `changed` holds `{ "entity": "<the role's ID>", "type": "CustomerRole", "created": true, "periods": [{ "from": "<1 March>", "until": "<1 October>" }] }` and the same for the PlayedBy relationship with `relationship` in place of `entity`
