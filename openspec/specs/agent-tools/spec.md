# agent-tools Specification

## Purpose
Defines how AI agents use a store through tools of the Model Context Protocol: describing the store, planning and registering schema changes, writing and reading data, and the approval policy that limits what an agent may do.

## Requirements

### Requirement: Tools over a store

THE SYSTEM SHALL serve a store as tools of the Model Context Protocol, each with a name, a description and a JSON Schema for its input. Every tool SHALL take text in the formats of the `schema-documents` and `json-payloads` capabilities where it takes documents or payloads, and SHALL return JSON. A tool call that cannot be done SHALL return problems as JSON, located in the document or payload where one was given, and SHALL NOT fail the protocol call. A problem with an argument of the call SHALL have the path `@arguments.<name>`.
The tools SHALL be: `cairn_describe`, `cairn_json_schema`, `cairn_explain_problem`, `cairn_plan_schema`, `cairn_register_schema`, `cairn_dry_run`, `cairn_commit`, `cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_timeline`, `cairn_navigate`, `cairn_traverse`, `cairn_query`, `cairn_list_changes` and `cairn_approval_status`.

#### Scenario: Listing the tools

- **WHEN** an agent connects to the server and lists its tools
- **THEN** the list holds every tool named in this requirement, each with a description and an input schema

#### Scenario: A malformed payload

- **WHEN** an agent calls `cairn_commit` with a payload whose first write has the key `upsert`
- **THEN** the tool returns a problem located at that write, and the protocol call itself succeeds

### Requirement: Describing the store

`cairn_describe` SHALL return the store's latest basis, the stored definition of every origin as a schema document, the available rules with their descriptions, types and codes and whether the store has their code, and the store's approval policy. `cairn_json_schema` SHALL return the published JSON Schema of schema documents, the generated JSON Schema of transaction payloads, the published JSON Schema of queries, or the generated JSON Schema of the values of a named type. `cairn_explain_problem` SHALL return the explanation of a problem code, or of a rule's code.

#### Scenario: Reading the schema as documents

- **WHEN** a store holds the reference domain model and an agent calls `cairn_describe`
- **THEN** the result holds a schema document for the origin `reference-model` that reads back to the stored definition, and lists the rules `lei-checksum` and `total-ownership-at-most-100`

#### Scenario: JSON Schema of a type's values

- **WHEN** an agent asks `cairn_json_schema` for the values of Organization
- **THEN** the result is the JSON Schema that requires `name` and describes `lei`

### Requirement: Planning and registering schema changes

`cairn_plan_schema` SHALL plan the registration of a schema document and return the plan as JSON, with its problems located in the document. `cairn_register_schema` SHALL register it, subject to the approval policy, and return the outcome: applied, with the basis and transaction time; unchanged; rejected, with located problems; or waiting for approval, with the approval request.

#### Scenario: An agent adds an optional attribute

- **WHEN** an agent reads the reference domain model with `cairn_describe`, adds the optional attribute `email` to Person, raises the origin's version, plans the document and registers it
- **THEN** the plan says the registration would be applied with a compatible change at `Person.email`, and the registration is applied

#### Scenario: An agent's breaking change

- **WHEN** an agent registers a version of the reference domain model without the attribute `birthDate` of Person
- **THEN** the registration is rejected with a breaking change at `Person.birthDate`, located at Person in the document, with a hint

### Requirement: Writing data

`cairn_dry_run` SHALL run a transaction payload dry and return either that it would be committed or its problems located in the payload. `cairn_commit` SHALL commit it, subject to the approval policy, and return the receipt as JSON, the located problems, or the approval request.

#### Scenario: An agent records an ownership

- **WHEN** ACME and Beta exist, and an agent commits a payload that relates ACME to Beta with Owns, with the share 30, from 1 March
- **THEN** the tool returns a receipt with the relationship's ID

#### Scenario: An agent fixes its own mistake

- **WHEN** an agent's dry run of a payload creating an Organization with the value `nmae` returns a problem whose hint names `name`, and the agent runs the corrected payload dry
- **THEN** the second dry run says the transaction would be committed

### Requirement: Reading data

`cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_navigate` and `cairn_traverse` SHALL read through a view whose coordinates the call gives: an effective time, and optionally a basis or a transaction time to read as of. `cairn_timeline` SHALL read the whole history in effective time, as of the latest basis or of the basis or transaction time the call gives. Every result SHALL report the coordinates it was read at. A read by an ID that the store does not know SHALL return a problem; an entity or relationship that is not effective at the given time SHALL read as nothing. `cairn_traverse` SHALL follow every relationship of the given name, up to the given maximum depth, or to the end when the call gives none. `cairn_read_entity`, `cairn_navigate` and `cairn_traverse` SHALL take, in place of the ID of the entity they read or start from, a unique key in the form of payloads (`key`), found at the call's effective time; a call SHALL give exactly one of the two, a key that is invalid SHALL return a problem of the code for invalid keys at `@arguments.key`, and a key that names no entity then SHALL read as nothing.

#### Scenario: Who owns Beta, as an agent

- **WHEN** the transactions of RD-2 are recorded, and an agent calls `cairn_navigate` from Beta by `ownedBy` effective at 1 March
- **THEN** the result is ACME, through an Owns relationship with the share 55, and the result reports its effective time and basis

#### Scenario: Reading an account by its number, as an agent

- **WHEN** the transactions of RD-5 are recorded, and an agent calls `cairn_read_entity` with the key `{ entityType: Account, key: { number: A-1 } }` effective at 31 March
- **THEN** the result is A-1 with the balance 1,200, and it reports its effective time and basis

#### Scenario: Traversing to the end, as an agent

- **WHEN** ACME owns 55% of Beta and Gamma owns 70% of ACME, and an agent calls `cairn_traverse` from Beta by `ownedBy` without a maximum depth, effective 1 June
- **THEN** the result holds ACME at depth 1 and Gamma at depth 2

### Requirement: Approval policy

An adapter that serves a store to agents SHALL have an approval policy that says, for schema registrations and for data commits, whether an agent's request is allowed, needs a person's approval, or is denied. By default both are allowed. WHEN a request needs approval, THE SYSTEM SHALL keep it as an approval request, with an ID, the request, who asked, for whom, why and when, and SHALL return the request instead of applying it. A request that would be rejected at the time it is made, and a registration that would change nothing, SHALL be answered at once instead of being kept. People SHALL list, approve and reject approval requests through the Java API; approving SHALL apply the request then, as if submitted at that moment, and record who approved it; rejecting SHALL apply nothing and record who rejected it and why. `cairn_approval_status` SHALL return a request's status and, once decided, its outcome. A denied request SHALL be rejected with a problem.

#### Scenario: A commit waiting for approval

- **WHEN** the policy requires approval for data commits, and an agent commits a payload that creates ACME
- **THEN** the tool returns an approval request with the status pending, and ACME does not exist
- **AND** after a person approves the request, ACME exists and `cairn_approval_status` reports the receipt

#### Scenario: Denied schema registrations

- **WHEN** the policy denies schema registrations, and an agent registers a schema document
- **THEN** the tool returns a problem, and the schema is unchanged

### Requirement: The adapter's principal

An adapter SHALL be configured with the principal it acts as, and MAY be configured with the party it acts for. It SHALL record its principal on every transaction it submits, schema or data. A payload that names another principal SHALL be rejected with a problem; a payload that names no party acted for SHALL get the configured one.

#### Scenario: An agent cannot act as someone else

- **WHEN** an adapter acts as `agent:onboarding`, and an agent commits a payload whose principal is `alice`
- **THEN** the tool returns a problem located at the principal, and nothing is recorded

### Requirement: A server for agents

THE SYSTEM SHALL provide a server that creates an in-memory store, registers the schema documents it is given at start, and serves the store's tools over standard input and output, with a principal and an approval policy given as options. The server SHALL run the rules provided on its class path through Java's service loader, and SHALL declare without code the other rules that the documents name. It SHALL include an expression language, and SHALL compile and run the checks that the documents declare.

#### Scenario: Starting a server with the reference model

- **WHEN** the server is started with the reference domain's schema document and the principal `agent:demo`, and an agent calls `cairn_describe`
- **THEN** the result holds the origin `reference-model`

#### Scenario: A server that runs checks

- **WHEN** the server is started with a document in which Account declares the check `balance-not-negative`, and an agent dry runs a payload that creates an account with the balance -5
- **THEN** the result holds a problem of the code `CHECK_VIOLATED` that names `balance-not-negative`

### Requirement: Querying

`cairn_query` SHALL run a query written as JSON, in the form of the `queries` capability, through a view whose coordinates the call gives, as the other reads do, and SHALL return the query's result as JSON with the coordinates it was read at, or the query's problems located in the text.

#### Scenario: An agent totals balances

- **WHEN** the transactions of RD-1, RD-2 and RD-5 are recorded, and an agent calls `cairn_query` with the query of the total balance by ultimate parent, effective 31 March
- **THEN** the result holds one row, ACME with 3200, and reports its effective time and basis

### Requirement: Reading changes

`cairn_list_changes` SHALL read the changes of the store after a basis that the call gives, 0 if it gives none, in basis order, at most as many as the call's limit, 100 if it gives none, and at most 1,000. It SHALL return the changes as JSON, in the form of the `json-payloads` capability, with `next`, the basis to give as `after` to continue, which is the basis of the last change returned or the given one if none, and the store's latest basis. An `after` below 0 or a limit outside 1 to 1,000 SHALL return a problem at `@arguments.after` or `@arguments.limit`. The tool only reads, so the approval policy SHALL NOT apply to it.

#### Scenario: An agent polls for changes

- **WHEN** the transactions of RD-1 are committed, an agent calls `cairn_list_changes` without a basis, the transactions of RD-3 are committed, and the agent calls it again with the `next` it received
- **THEN** the second result holds only RD-3's change, which lists the Employs relationship of Beta and Alice as created, and its `next` is RD-3's basis

#### Scenario: A limit out of range

- **WHEN** an agent calls `cairn_list_changes` with the limit 0
- **THEN** the result is a problem at `@arguments.limit`
