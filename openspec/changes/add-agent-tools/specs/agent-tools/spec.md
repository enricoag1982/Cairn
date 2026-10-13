# Spec Delta

## Purpose

Defines how AI agents use a store through tools of the Model Context Protocol: describing the store, planning and registering schema changes, writing and reading data, and the approval policy that limits what an agent may do.

## ADDED Requirements

### Requirement: Tools over a store

THE SYSTEM SHALL serve a store as tools of the Model Context Protocol, each with a name, a description and a JSON Schema for its input. Every tool SHALL take text in the formats of the `schema-documents` and `json-payloads` capabilities where it takes documents or payloads, and SHALL return JSON. A tool call that cannot be done SHALL return problems as JSON, located in the document or payload where one was given, and SHALL NOT fail the protocol call. A problem with an argument of the call SHALL have the path `@arguments.<name>`.
The tools SHALL be: `cairn_describe`, `cairn_json_schema`, `cairn_explain_problem`, `cairn_plan_schema`, `cairn_register_schema`, `cairn_dry_run`, `cairn_commit`, `cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_timeline`, `cairn_navigate`, `cairn_traverse` and `cairn_approval_status`.

#### Scenario: Listing the tools

- **WHEN** an agent connects to the server and lists its tools
- **THEN** the list holds every tool named in this requirement, each with a description and an input schema

#### Scenario: A malformed payload

- **WHEN** an agent calls `cairn_commit` with a payload whose first write has the key `upsert`
- **THEN** the tool returns a problem located at that write, and the protocol call itself succeeds

### Requirement: Describing the store

`cairn_describe` SHALL return the store's latest basis, the stored definition of every origin as a schema document, the available rules with their descriptions, types and codes and whether the store has their code, and the store's approval policy. `cairn_json_schema` SHALL return the published JSON Schema of schema documents, the generated JSON Schema of transaction payloads, or the generated JSON Schema of the values of a named type. `cairn_explain_problem` SHALL return the explanation of a problem code, or of a rule's code.

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

`cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_navigate` and `cairn_traverse` SHALL read through a view whose coordinates the call gives: an effective time, and optionally a basis or a transaction time to read as of. `cairn_timeline` SHALL read the whole history in effective time, as of the latest basis or of the basis or transaction time the call gives. Every result SHALL report the coordinates it was read at. A read by an ID that the store does not know SHALL return a problem; an entity or relationship that is not effective at the given time SHALL read as nothing. `cairn_traverse` SHALL follow every relationship of the given name, up to the given maximum depth.

#### Scenario: Who owns Beta, as an agent

- **WHEN** the transactions of RD-2 are recorded, and an agent calls `cairn_navigate` from Beta by `ownedBy` effective at 1 March
- **THEN** the result is ACME, through an Owns relationship with the share 55, and the result reports its effective time and basis

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

THE SYSTEM SHALL provide a server that creates an in-memory store, registers the schema documents it is given at start, and serves the store's tools over standard input and output, with a principal and an approval policy given as options. The server SHALL run the rules provided on its class path through Java's service loader, and SHALL declare without code the other rules that the documents name.

#### Scenario: Starting a server with the reference model

- **WHEN** the server is started with the reference domain's schema document and the principal `agent:demo`, and an agent calls `cairn_describe`
- **THEN** the result holds the origin `reference-model`
