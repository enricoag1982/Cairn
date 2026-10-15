## MODIFIED Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema, or was a purge (capability `purge`).
A transaction SHALL be validated against the state it would produce. WHEN any write is invalid, THE SYSTEM SHALL reject the transaction with every problem found and record nothing. Problems SHALL come in a fixed order: those of the transaction as a whole, which have the path `@transaction`, then those of each write in order, then the problems of each entity and relationship in the order the transaction first touches it (the ends of a relationship that a write relates are touched before that relationship), each one's declarative problems before its integrity problems (see the `relationships` capability), and then the problems of rules (see the `entity-rules` capability), entity and relationship by entity and relationship in the same order. A transaction without writes, or whose writes change nothing, SHALL still be committed and recorded.
The receipt of a committed transaction SHALL contain its transaction time, its basis, the ID of every entity and relationship it created, the IDs of the relationships and entities that composition cascades ended (see the `relationships` capability), in the order the cascades reached them, depth first, and the principal, the party acted for and the reason it recorded.

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

### Requirement: Idempotency keys

A transaction MAY carry an idempotency key. WHEN a transaction with a key that a committed transaction already used is submitted with the same writes, principal, party acted for and reason, THE SYSTEM SHALL return the earlier receipt and record nothing. The key SHALL be checked before the writes are validated, so a retry gets the earlier receipt even if the same writes would now be rejected, and a dry run of a retry SHALL report that it would be committed. WHEN the key was used with different content, THE SYSTEM SHALL reject the transaction with that one problem, located at `@transaction`. WHEN the transaction that used the key wrote data that a purge has since removed, THE SYSTEM SHALL reject the transaction, and a dry run of it, with that one problem, located at `@transaction`, whatever its content (capability `purge`).

#### Scenario: Retrying a committed transaction

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and the same transaction is submitted again
- **THEN** the second submission returns the first receipt, and no second transaction is recorded

#### Scenario: Reusing a key for other writes

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and a transaction with the same key but other writes is submitted
- **THEN** the second transaction is rejected with a problem

#### Scenario: Retrying writes that are now invalid

- **WHEN** a transaction with the idempotency key `onboard-acme` created ACME with the LEI `5493001KJTIIGC8Y1R12`, a later transaction gave Gamma that LEI after ACME's LEI was removed, and the first transaction is submitted again
- **THEN** the submission returns the first receipt, and nothing is recorded
