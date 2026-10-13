## MODIFIED Requirements

### Requirement: Tools over a store

THE SYSTEM SHALL serve a store as tools of the Model Context Protocol, each with a name, a description and a JSON Schema for its input. Every tool SHALL take text in the formats of the `schema-documents` and `json-payloads` capabilities where it takes documents or payloads, and SHALL return JSON. A tool call that cannot be done SHALL return problems as JSON, located in the document or payload where one was given, and SHALL NOT fail the protocol call. A problem with an argument of the call SHALL have the path `@arguments.<name>`.
The tools SHALL be: `cairn_describe`, `cairn_json_schema`, `cairn_explain_problem`, `cairn_plan_schema`, `cairn_register_schema`, `cairn_dry_run`, `cairn_commit`, `cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_timeline`, `cairn_navigate`, `cairn_traverse`, `cairn_query` and `cairn_approval_status`.

#### Scenario: Listing the tools

- **WHEN** an agent connects to the server and lists its tools
- **THEN** the list holds every tool named in this requirement, each with a description and an input schema

#### Scenario: A malformed payload

- **WHEN** an agent calls `cairn_commit` with a payload whose first write has the key `upsert`
- **THEN** the tool returns a problem located at that write, and the protocol call itself succeeds

## ADDED Requirements

### Requirement: Querying

`cairn_query` SHALL run a query written as JSON, in the form of the `queries` capability, through a view whose coordinates the call gives, as the other reads do, and SHALL return the query's result as JSON with the coordinates it was read at, or the query's problems located in the text.

#### Scenario: An agent totals balances

- **WHEN** the transactions of RD-1, RD-2 and RD-5 are recorded, and an agent calls `cairn_query` with the query of the total balance by ultimate parent, effective 31 March
- **THEN** the result holds one row, ACME with 3200, and reports its effective time and basis
