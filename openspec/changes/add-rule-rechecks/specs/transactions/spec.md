## MODIFIED Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema, or was a purge (capability `purge`).
A transaction SHALL be validated against the state it would produce. WHEN any write is invalid, THE SYSTEM SHALL reject the transaction with every problem found and record nothing. Problems SHALL come in a fixed order: those of the transaction as a whole, which have the path `@transaction`, then those of each write in order, then the problems of each entity and relationship in the order the transaction first touches it (the ends of a relationship that a write relates are touched before that relationship), each one's declarative problems before its integrity problems (see the `relationships` capability), then the problems of rules (see the `entity-rules` capability), entity and relationship by entity and relationship in the same order, and last the problems of re-checks, in the order of the IDs of the entities and relationships they check. A transaction without writes, or whose writes change nothing, SHALL still be committed and recorded.
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

#### Scenario: Problems of re-checks come last

- **WHEN** Owns names `controlling-owner-has-lei`, ACME has an LEI and owns 60% of Beta from 1 February, and a transaction clears ACME's LEI from 1 June and creates Gamma with the LEI `5493001KJTIIGC8Y1R13` (RD-12)
- **THEN** the transaction is rejected with the problem from `lei-checksum` about Gamma first, and then the problem from `controlling-owner-has-lei` about ACME's Owns of Beta
