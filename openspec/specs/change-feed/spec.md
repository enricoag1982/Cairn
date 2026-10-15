# change-feed Specification

## Purpose
Defines the change feed: the changes of a store, one per basis in basis order, what a change says about the entities and relationships a transaction changed, reading them from a basis or through a subscription, and what purges and reopening do to them.

## Requirements

### Requirement: One change per basis

THE SYSTEM SHALL give, for every basis of a store, one change, in basis order, without gaps: for a data transaction, its transaction record and what it changed; for a schema registration, its schema transaction; for a purge, its purge record. A dry run, a rejected transaction and a registration that changes nothing take no basis and SHALL leave no change. Reading the changes after a basis, with a limit, SHALL return the changes at the bases after it, in basis order, at most as many as the limit; reading after the latest basis SHALL return nothing, and a limit below 1 SHALL be refused with an exception. Every kind of store SHALL give the same changes for the same transactions.

#### Scenario: Changes in basis order

- **WHEN** the reference domain model is registered, the transactions of RD-1 and RD-3 are committed, the transaction of RD-12 is rejected, a transaction is dry run, and the changes after no basis are read
- **THEN** there are three changes, in basis order: the registration with its schema transaction, then RD-1's and RD-3's transactions with their transaction records

#### Scenario: Reading from a basis with a limit

- **WHEN** the transactions of RD-1 to RD-5 are committed, and the changes after RD-1's basis are read with a limit of 2, and then the changes after the last basis returned
- **THEN** the first read returns the changes at the two bases after RD-1's, and the second continues at the next basis, until the latest

### Requirement: What a transaction changed

THE SYSTEM SHALL list, in the change of a data transaction, every entity and relationship whose timeline at the transaction's basis differs from its timeline at the basis before, ordered by ID, each with its ID, the name of its type at that basis, whether the transaction created it, and the effective periods in which a view at the basis and a view at the basis before read different versions of it, being absent included. A write that leaves the values of a period as they were records no new version there, so it SHALL NOT make that period changed; a transaction that changes nothing SHALL keep its change, with its transaction record and nothing listed. The periods SHALL be ordered and SHALL neither overlap nor touch. What composition cascades end SHALL be listed like what the transaction names.

#### Scenario: Entities created

- **WHEN** the transactions of RD-1 are committed
- **THEN** the change of RD-1 lists ACME, Beta and Alice, each created, with their types, changed from 1 January with no end

#### Scenario: A correction changes a later period only

- **WHEN** the transactions of RD-5 are committed
- **THEN** the change of the correction recorded on 20 April lists A-1, not created, changed from 15 March with no end, and nothing else

#### Scenario: A closed period

- **WHEN** the transactions of RD-4 are committed
- **THEN** the change lists Beta's customer role and its PlayedBy relationship, both created, each changed from 1 March until 1 October

#### Scenario: A transaction that changes nothing

- **WHEN** the transactions of RD-1 are committed, and a transaction replaces ACME's values from 1 January with the same values
- **THEN** its change has its transaction record and lists nothing

#### Scenario: Ended by a cascade

- **WHEN** Beta has an address through HasAddress and no other relationship, and a transaction ends Beta from 1 June
- **THEN** its change lists Beta, its HasAddress relationship and the address, none created, each changed from 1 June with no end

### Requirement: Subscribing to changes

THE SYSTEM SHALL let a consumer subscribe to the changes after a basis through `java.util.concurrent.Flow`. A subscription SHALL deliver each change at a basis after the given one once, in basis order, only as many as the consumer requested, on a thread that is not the one that committed, and SHALL wait for further commits when it has delivered every change. Commits SHALL NOT wait for a subscription. A subscriber that throws from `onNext` SHALL have its subscription cancelled, and the store SHALL go on. A request for fewer than one change SHALL end the subscription with an error. Closing a store SHALL complete its subscriptions.

#### Scenario: A subscription from a basis

- **WHEN** a consumer subscribes to the changes after RD-1's basis and requests ten, and the transactions of RD-3 and RD-4 are then committed
- **THEN** the consumer receives the changes of RD-3 and RD-4, in basis order, once each, on a thread other than the committing one

#### Scenario: A consumer that requests one at a time

- **WHEN** a consumer subscribes to the changes after no basis and requests one, and the transactions of RD-1 to RD-3 are committed
- **THEN** every commit returns, the consumer receives one change, and it receives the next only after it requests another

#### Scenario: A subscriber that throws

- **WHEN** a subscriber throws from `onNext`, and a further transaction is committed
- **THEN** the commit succeeds, and the subscriber receives nothing more

#### Scenario: Closing completes subscriptions

- **WHEN** a consumer has subscribed to the changes of a durable store, and the store is closed
- **THEN** the consumer's subscription completes

### Requirement: Changes after a purge

WHEN a purge removes entities, THE SYSTEM SHALL leave no change that names what it removed, and SHALL keep the changes of the transactions that wrote them, with their records and what is left of what they changed. The purge SHALL have a change of its own with its purge record.

#### Scenario: A purged person leaves the feed

- **WHEN** the transactions of RD-1 and RD-3 are committed, Alice is purged, and the changes are read
- **THEN** the change of RD-1 lists ACME and Beta only, the change of RD-3 keeps its transaction record and lists nothing, and the last change is the purge with its record

### Requirement: Changes after reopening

A durable store and a RocksDB store SHALL give, after they are opened again, the same changes as before they were closed.

#### Scenario: The same changes after reopening

- **WHEN** the reference domain model is registered and the transactions of RD-1 to RD-5 are committed to a durable store, which is closed and opened again
- **THEN** the changes read after no basis are the same as before closing
