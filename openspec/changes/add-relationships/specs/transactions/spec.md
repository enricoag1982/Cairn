## MODIFIED Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema.
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

### Requirement: Temporary IDs

A write that creates an entity or relates two entities SHALL be able to name what it creates with a temporary ID, which other writes of the same transaction use to refer to it. Entities and relationships share one namespace of temporary IDs. The receipt SHALL map every temporary ID to the ID of the entity or relationship. A temporary ID MUST NOT name two created entities or relationships, and a write MUST NOT refer to a temporary ID that no earlier write of the same transaction creates.

#### Scenario: Referring to an entity created in the same transaction

- **WHEN** a transaction creates ACME with the temporary ID `acme`, and patches `acme`'s name from 1 March
- **THEN** the transaction is committed, and the receipt maps `acme` to ACME's ID

#### Scenario: Unknown temporary ID

- **WHEN** a transaction patches the temporary ID `beta`, which it does not create
- **THEN** the transaction is rejected with a problem located at `$beta`

#### Scenario: Temporary ID used before it is created

- **WHEN** a transaction patches `$acme` in its first write and creates ACME with the temporary ID `acme` in its second
- **THEN** the transaction is rejected with a problem located at `$acme`

### Requirement: Entity IDs

Every entity and every relationship SHALL have an ID that never changes. Entities and relationships share one namespace of IDs. A store SHALL use one ID generator for both, chosen when the store is created: UUIDv7 by default, or a deterministic generator that produces the same IDs for the same sequence of creations. A write that creates an entity or a relationship MAY supply its ID instead, in the store's format. IDs from the default generator are lowercase, hyphenated UUIDs. Creating an entity or a relationship with an ID that already exists, for an entity or a relationship, or that the generator's format does not allow, SHALL be a problem.

#### Scenario: Default IDs are UUIDv7

- **WHEN** a store created with default settings creates an entity
- **THEN** the entity's ID is a version 7 UUID

#### Scenario: Supplying an ID that exists

- **WHEN** an entity is created with a supplied ID, and a later transaction creates another entity with the same ID
- **THEN** the later transaction is rejected with a problem

#### Scenario: Deterministic IDs

- **WHEN** two stores created with the deterministic generator commit the same transactions
- **THEN** their entities have the same IDs
