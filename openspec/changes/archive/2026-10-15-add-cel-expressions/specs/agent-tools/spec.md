## MODIFIED Requirements

### Requirement: A server for agents

THE SYSTEM SHALL provide a server that creates an in-memory store, registers the schema documents it is given at start, and serves the store's tools over standard input and output, with a principal and an approval policy given as options. The server SHALL run the rules provided on its class path through Java's service loader, and SHALL declare without code the other rules that the documents name. It SHALL include an expression language, and SHALL compile and run the checks that the documents declare.

#### Scenario: Starting a server with the reference model

- **WHEN** the server is started with the reference domain's schema document and the principal `agent:demo`, and an agent calls `cairn_describe`
- **THEN** the result holds the origin `reference-model`

#### Scenario: A server that runs checks

- **WHEN** the server is started with a document in which Account declares the check `balance-not-negative`, and an agent dry runs a payload that creates an account with the balance -5
- **THEN** the result holds a problem of the code `CHECK_VIOLATED` that names `balance-not-negative`

