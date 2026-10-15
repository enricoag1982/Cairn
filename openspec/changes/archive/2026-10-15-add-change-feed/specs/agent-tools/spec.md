## MODIFIED Requirements

### Requirement: Tools over a store

THE SYSTEM SHALL serve a store as tools of the Model Context Protocol, each with a name, a description and a JSON Schema for its input. Every tool SHALL take text in the formats of the `schema-documents` and `json-payloads` capabilities where it takes documents or payloads, and SHALL return JSON. A tool call that cannot be done SHALL return problems as JSON, located in the document or payload where one was given, and SHALL NOT fail the protocol call. A problem with an argument of the call SHALL have the path `@arguments.<name>`.
The tools SHALL be: `cairn_describe`, `cairn_json_schema`, `cairn_explain_problem`, `cairn_plan_schema`, `cairn_register_schema`, `cairn_dry_run`, `cairn_commit`, `cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_timeline`, `cairn_navigate`, `cairn_traverse`, `cairn_query`, `cairn_list_changes` and `cairn_approval_status`.

#### Scenario: Listing the tools

- **WHEN** an agent connects to the server and lists its tools
- **THEN** the list holds every tool named in this requirement, each with a description and an input schema

#### Scenario: A malformed payload

- **WHEN** an agent calls `cairn_commit` with a payload whose first write has the key `upsert`
- **THEN** the tool returns a problem located at that write, and the protocol call itself succeeds

## ADDED Requirements

### Requirement: Reading changes

`cairn_list_changes` SHALL read the changes of the store after a basis that the call gives, 0 if it gives none, in basis order, at most as many as the call's limit, 100 if it gives none, and at most 1,000. It SHALL return the changes as JSON, in the form of the `json-payloads` capability, with `next`, the basis to give as `after` to continue, which is the basis of the last change returned or the given one if none, and the store's latest basis. An `after` below 0 or a limit outside 1 to 1,000 SHALL return a problem at `@arguments.after` or `@arguments.limit`. The tool only reads, so the approval policy SHALL NOT apply to it.

#### Scenario: An agent polls for changes

- **WHEN** the transactions of RD-1 are committed, an agent calls `cairn_list_changes` without a basis, the transactions of RD-3 are committed, and the agent calls it again with the `next` it received
- **THEN** the second result holds only RD-3's change, which lists the Employs relationship of Beta and Alice as created, and its `next` is RD-3's basis

#### Scenario: A limit out of range

- **WHEN** an agent calls `cairn_list_changes` with the limit 0
- **THEN** the result is a problem at `@arguments.limit`
