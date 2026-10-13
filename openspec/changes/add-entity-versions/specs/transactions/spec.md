# Spec Delta

## Purpose

Defines how writes are committed: atomic transactions with receipts, entity IDs, temporary and supplied IDs, who and why, dry runs and idempotency keys.

## ADDED Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema.
A transaction SHALL be validated against the state it would produce. WHEN any write is invalid, THE SYSTEM SHALL reject the transaction with every problem found and record nothing.
The receipt of a committed transaction SHALL contain its transaction time, its basis, the ID of every entity it created, and the principal, the party acted for and the reason it recorded.

#### Scenario: Creating several entities in one transaction

- **WHEN** a transaction recorded on 5 January creates the organizations ACME and Beta and the person Alice, each effective from 1 January (RD-1)
- **THEN** the transaction is committed, and its receipt contains its basis, its transaction time and an ID for each of the three entities

#### Scenario: One invalid write rejects the transaction

- **WHEN** a transaction creates ACME, and also creates Beta without its required attribute `name`
- **THEN** the transaction is rejected with a problem about Beta
- **AND** ACME does not exist

#### Scenario: Data and schema share one sequence

- **WHEN** a schema transaction is followed by a data transaction
- **THEN** the data transaction's basis and transaction time are greater than those of the schema transaction

### Requirement: Who and why

Every transaction SHALL name the principal that submits it, and MAY name the party the principal acts for and a reason. THE SYSTEM SHALL record them with the transaction and SHALL list them in the store's transaction history, together with each transaction's basis and transaction time.

#### Scenario: An agent acting for a person

- **WHEN** the principal `agent:onboarding` commits a transaction on behalf of `alice` with the reason "New customer"
- **THEN** the store's transaction history lists that transaction with the principal `agent:onboarding`, the party `alice` and the reason "New customer"

#### Scenario: Missing principal

- **WHEN** a transaction names no principal
- **THEN** the transaction is rejected with a problem

### Requirement: Temporary IDs

A write that creates an entity SHALL be able to name it with a temporary ID, which other writes of the same transaction use to refer to it. The receipt SHALL map every temporary ID to the entity's ID. A temporary ID MUST NOT name two created entities, and a write MUST NOT refer to a temporary ID that the transaction does not create.

#### Scenario: Referring to an entity created in the same transaction

- **WHEN** a transaction creates ACME with the temporary ID `acme`, and patches `acme`'s name from 1 March
- **THEN** the transaction is committed, and the receipt maps `acme` to ACME's ID

#### Scenario: Unknown temporary ID

- **WHEN** a transaction patches the temporary ID `beta`, which it does not create
- **THEN** the transaction is rejected with a problem located at `$beta`

### Requirement: Entity IDs

Every entity SHALL have an ID that never changes. A store SHALL use one ID generator, chosen when the store is created: UUIDv7 by default, or a deterministic generator that produces the same IDs for the same sequence of creations. A write that creates an entity MAY supply its ID instead, in the store's format. Creating an entity with an ID that already exists, or that the generator's format does not allow, SHALL be a problem.

#### Scenario: Default IDs are UUIDv7

- **WHEN** a store created with default settings creates an entity
- **THEN** the entity's ID is a version 7 UUID

#### Scenario: Supplying an ID that exists

- **WHEN** an entity is created with a supplied ID, and a later transaction creates another entity with the same ID
- **THEN** the later transaction is rejected with a problem

#### Scenario: Deterministic IDs

- **WHEN** two stores created with the deterministic generator commit the same transactions
- **THEN** their entities have the same IDs

### Requirement: Dry runs

THE SYSTEM SHALL validate a transaction as a dry run without committing it. A dry run SHALL report every problem that committing would report, or, when there is none, that the transaction would be committed. A dry run MUST NOT record anything, use a basis, or reserve an ID.

#### Scenario: Dry run of a valid transaction

- **WHEN** a transaction that creates ACME is run dry
- **THEN** the dry run reports that it would be committed
- **AND** ACME does not exist, and the store's latest basis is unchanged

#### Scenario: Dry run of an invalid transaction

- **WHEN** a transaction that creates an Organization without a name is run dry
- **THEN** the dry run reports the same problem that committing would report

### Requirement: Idempotency keys

A transaction MAY carry an idempotency key. WHEN a transaction with a key that a committed transaction already used is submitted with the same writes, principal, party acted for and reason, THE SYSTEM SHALL return the earlier receipt and record nothing. WHEN the key was used with different content, THE SYSTEM SHALL reject the transaction with a problem.

#### Scenario: Retrying a committed transaction

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and the same transaction is submitted again
- **THEN** the second submission returns the first receipt, and no second transaction is recorded

#### Scenario: Reusing a key for other writes

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and a transaction with the same key but other writes is submitted
- **THEN** the second transaction is rejected with a problem
