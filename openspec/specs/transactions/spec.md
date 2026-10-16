# transactions Specification

## Purpose
Defines how writes are committed: atomic transactions with receipts, entity IDs, temporary and supplied IDs, who and why, dry runs and idempotency keys.

## Requirements

### Requirement: Committing a transaction

THE SYSTEM SHALL commit a transaction, a list of writes, atomically: either every write is recorded or none is. A committed transaction SHALL get a transaction time and a basis, each greater than those of every earlier transaction of the store, whether it changed data or the schema, or was a purge (capability `purge`).
A transaction SHALL be validated against the state it would produce. WHEN any write is invalid, THE SYSTEM SHALL reject the transaction with every problem found and record nothing. Problems SHALL come in a fixed order: those of the transaction as a whole, which have the path `@transaction`, then those of each write in order, then the problems of each entity and relationship in the order the transaction first touches it (the ends of a relationship that a write relates are touched before that relationship), each one's declarative problems before its integrity problems (see the `relationships` capability), then the problems of rules (see the `entity-rules` capability), entity and relationship by entity and relationship in the same order, and last the problems of re-checks, in the order of the IDs of the entities and relationships they check, then in the order in which their types name the rules, then in the order of the versions. A transaction without writes, or whose writes change nothing, SHALL still be committed and recorded.
The receipt of a committed transaction SHALL contain its transaction time, its basis, the ID of every entity and relationship it created, the IDs of the relationships and entities that composition cascades ended (see the `relationships` capability), in the order the cascades reached them, depth first, what each unique key that a write names resolved to (see "Unique keys"), and the principal, the party acted for and the reason it recorded.

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

### Requirement: Who and why

Every transaction SHALL name the principal that submits it, and MAY name the party the principal acts for and a reason. THE SYSTEM SHALL record them with the transaction and SHALL list them in the store's transaction history, together with each transaction's basis and transaction time.

#### Scenario: An agent acting for a person

- **WHEN** the principal `agent:onboarding` commits a transaction on behalf of `alice` with the reason "New customer"
- **THEN** the store's transaction history lists that transaction with the principal `agent:onboarding`, the party `alice` and the reason "New customer"

#### Scenario: Missing principal

- **WHEN** a transaction names no principal
- **THEN** the transaction is rejected with a problem

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

A transaction MAY carry an idempotency key. WHEN a transaction with a key that a committed transaction already used is submitted with the same writes, principal, party acted for, reason and clearance (see the `attribute-access` capability), THE SYSTEM SHALL return the earlier receipt and record nothing. The key SHALL be checked before the writes are validated, after a transaction with a clearance is refused for writes that its clearance does not allow (see the `attribute-access` capability), so a retry gets the earlier receipt even if the same writes would now be rejected, and a dry run of a retry SHALL report that it would be committed. WHEN the key was used with different content, THE SYSTEM SHALL reject the transaction with that one problem, located at `@transaction`. WHEN the transaction that used the key wrote data that a purge has since removed, or named an entity that a purge has since removed, by its ID or by a unique key, THE SYSTEM SHALL reject the transaction, and a dry run of it, with that one problem, located at `@transaction`, whatever its content (capability `purge`).

#### Scenario: Retrying a committed transaction

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and the same transaction is submitted again
- **THEN** the second submission returns the first receipt, and no second transaction is recorded

#### Scenario: Reusing a key for other writes

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and a transaction with the same key but other writes is submitted
- **THEN** the second transaction is rejected with a problem

#### Scenario: Retrying writes that are now invalid

- **WHEN** a transaction with the idempotency key `onboard-acme` created ACME with the LEI `5493001KJTIIGC8Y1R12`, a later transaction gave Gamma that LEI after ACME's LEI was removed, and the first transaction is submitted again
- **THEN** the submission returns the first receipt, and nothing is recorded

#### Scenario: A retry with another clearance

- **WHEN** a transaction with the idempotency key `fund-a1` and the clearance `finance` is committed, and the same transaction is submitted again without a clearance
- **THEN** the second submission is rejected with the problem for a key used with different content, located at `@transaction`

### Requirement: Unique keys

A write SHALL be able to name an existing entity by a *unique key*: an entity type, an attribute that is unique in that type, and a value. THE SYSTEM SHALL resolve a key at the start of the write's effective period, against the store as committed before the transaction, to the entity of that type, or of one of its subtypes, that has the value then, and SHALL then treat the write as if it named that entity's ID: problems about the entity SHALL name it by its ID. A key MUST name an entity type of the schema, an attribute of that type, or an alias of one, that is unique, and a value that fits the attribute's value type; otherwise THE SYSTEM SHALL reject the transaction with a problem of the code for invalid keys, which lists the unique attributes of the type where it has some. WHEN no entity has a valid key at that time, THE SYSTEM SHALL reject the transaction with a problem of the code for keys that name no entity. Both problems SHALL have the path of the write (`@write.` followed by its position, counted from 0) and SHALL name the key in their message. The transaction as submitted, which idempotency keys compare and durable stores record, SHALL keep its keys. The receipt SHALL list, in the order of the writes, each key that a write names, with the position of the write and the ID of the entity it resolved to.

#### Scenario: Patching an account by its number

- **WHEN** A-1 exists with the balance 1,000 (RD-5), and a transaction patches the Account with the number `A-1` to the balance 1,500 from 15 March
- **THEN** the transaction is committed, A-1's balance effective 31 March is 1,500, and the receipt lists the key for the write at position 0 with A-1's ID

#### Scenario: Relating by a key

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` from 1 January (RD-9), and a transaction relates the Organization with that LEI to Alice with Employs, from 1 March
- **THEN** the transaction is committed, and ACME employs Alice effective 1 March

#### Scenario: A key whose value moved

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` until 1 March, Gamma has it from 1 March, and a transaction patches the Organization with that LEI to the name "Gamma Holdings" from 1 April
- **THEN** the transaction is committed, Gamma's name effective 1 April is "Gamma Holdings", and ACME's name is unchanged

#### Scenario: A key that names no entity

- **WHEN** a transaction's first write patches the Account with the number `A-9`, which no account has
- **THEN** the transaction is rejected with a problem of the code for keys that name no entity, at the path `@write.0`

#### Scenario: A key on an attribute that is not unique

- **WHEN** a transaction patches the Account with the currency `EUR`
- **THEN** the transaction is rejected with a problem of the code for invalid keys, whose expected value names `number`
