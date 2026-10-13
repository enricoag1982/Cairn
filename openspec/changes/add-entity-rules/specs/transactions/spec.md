## MODIFIED Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema.
A transaction SHALL be validated against the state it would produce. WHEN any write is invalid, THE SYSTEM SHALL reject the transaction with every problem found and record nothing. Problems SHALL come in a fixed order: those of the transaction as a whole, which have the path `@transaction`, then those of each write in order, then the declarative problems of each entity in the order the transaction first touches it, and then the problems of rules (see the `entity-rules` capability), entity by entity in the same order. A transaction without writes, or whose writes change nothing, SHALL still be committed and recorded.
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
