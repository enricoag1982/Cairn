# purge Specification

## Purpose
Defines purges: removing entities, their relationships and the targets of their compositions from all of history, for legal erasure; when a purge is refused, the record it leaves, what can never be used again, and purges in durable stores.

## Requirements

### Requirement: Purging entities

THE SYSTEM SHALL purge the entities that a purge names, with a principal, optionally a party acted for, and a reason that MUST NOT be blank. A purge SHALL remove every version of those entities at every basis, every relationship from or to them with all its versions, and the targets of their compositions that no other entity ever composed, with their own relationships. After a purge, every view, at every basis and including views opened before it, and every timeline and query SHALL read as if the purged entities and relationships had never been recorded; this overrides every other requirement that recorded history, or what a view reads, never changes. A purge writes no versions, so rules SHALL NOT run. Nothing else SHALL change.

#### Scenario: Purging Alice

- **WHEN** the transactions of RD-1 and RD-3 are recorded, and Alice is purged
- **THEN** no view at any basis returns Alice or the `Employs` relationship of RD-3, and the timelines of ACME and Beta are unchanged

#### Scenario: The targets of compositions go too

- **WHEN** the transactions of RD-1 are recorded, Alice has an Address through `HasAddress`, and Alice is purged
- **THEN** neither the Address nor the `HasAddress` relationship can be read at any basis

### Requirement: Refused purges

WHEN a purge names an ID that no entity of the store has, or when what would remain after it, as known at the latest basis, breaks the minimum of a relationship type at the other end, such as an entity left without a relationship that its type requires, or the target of a composition that another entity composed too, left without a source for a while, THE SYSTEM SHALL reject the purge with problems, SHALL change nothing, and SHALL use no basis. A problem about a minimum SHALL name the entity that would break it, so that the caller can purge it too.

#### Scenario: An account left without a holder

- **WHEN** the transactions of RD-1 and RD-5 are recorded, and Beta is purged alone
- **THEN** the purge is rejected with a problem that names A-1, which must have a holder, and Beta still exists

#### Scenario: A target that another party composed too

- **WHEN** the transactions of RD-1 are recorded, an Address belongs to Alice through `HasAddress` until 1 March and to ACME from 1 March, and Alice is purged
- **THEN** the purge is rejected with a problem that names the Address, and Alice still exists

#### Scenario: Purging a holder with its account

- **WHEN** the transactions of RD-1 and RD-5 are recorded, and Beta and A-1 are purged together
- **THEN** the purge succeeds, and A-2 and ACME are unchanged

### Requirement: Purge records

A purge SHALL be recorded as a transaction of its own, with a basis and a transaction time, its principal, the party acted for, its reason, and the numbers of entities and relationships it removed, but not what it removed. A store SHALL list its purge records, and SHALL tell whether an ID was purged without listing the purged IDs. A purge SHALL return the IDs it removed to its caller, once. The records of earlier transactions SHALL remain, with their principals and reasons.

#### Scenario: The record of Alice's purge

- **WHEN** the transactions of RD-1 and RD-3 are recorded, and the principal `dpo` purges Alice with the reason "Erasure request"
- **THEN** the purge returns Alice's ID and the ID of the `Employs` relationship, the store lists a purge record by `dpo` with that reason, one entity and one relationship, at the basis after RD-3's, the store tells that Alice's ID was purged, and it still lists the transactions of RD-1 and RD-3

### Requirement: What a purge leaves unusable

The ID of a purged entity or relationship SHALL never be given to a new one, and SHALL be refused when a write supplies it. A transaction that repeats the idempotency key of an earlier transaction that wrote purged data or named a purged entity, by its ID or by a unique key (see the `transactions` capability), SHALL be rejected with a problem, so that a retry cannot record the purged data again, and the store SHALL NOT keep the earlier transaction's writes.

#### Scenario: A purged ID supplied again

- **WHEN** Alice is purged, and a transaction creates a Person with Alice's ID as its supplied ID
- **THEN** the transaction is rejected with a problem at that write

#### Scenario: A retry after a purge

- **WHEN** the transaction of RD-1 is committed with an idempotency key, Alice is purged, and the same transaction is committed again with the same key
- **THEN** the commit is rejected with a problem about the key, and Alice does not exist

#### Scenario: A key that named a purged entity

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` (RD-9), a transaction with an idempotency key patches the Organization with that LEI, ACME is purged, and the same transaction is committed again with the same key
- **THEN** the second commit is rejected with the problem for idempotency keys of purged data, and no file of the store holds the LEI

### Requirement: Purges in durable stores

A durable store kept in a directory SHALL make a purge durable before it returns: it SHALL write the purge to its commit log, write a snapshot file without the purged data, and delete every older snapshot file and log segment, so that no file of the store holds the purged data. If the store stops after the purge reached its log, recovery SHALL complete the purge before the store opens. A RocksDB store (see the `durability` capability) SHALL write the purge in one synchronous write, and compact its files before the purge returns, so that no file of the store holds the purged data, including the keys that name it; a crash before the purge returns SHALL leave either the purge or nothing of it, and if the purge was written, opening the store SHALL finish compacting its files before the store opens.

#### Scenario: Nothing left on disk

- **WHEN** the transactions of RD-1 and RD-3 are committed to a durable store, Alice is purged, and the store is closed and opened again
- **THEN** no view of the store returns Alice, and no file in its directory contains the text `Alice`

#### Scenario: A purge interrupted

- **WHEN** a durable store writes a purge of Alice to its log and stops before the purge returns, and the store is opened
- **THEN** the store has no Alice, and no file in its directory contains the text `Alice`

#### Scenario: Nothing left in a RocksDB store's files

- **WHEN** the transactions of RD-1 and RD-3 are committed to a RocksDB store, Alice is purged, and the store is closed and opened again
- **THEN** no view of the store returns Alice, and no file in its directory contains the text `Alice`

#### Scenario: Nothing of a purge left in an open RocksDB store's files

- **WHEN** the transactions of RD-1 and RD-9 are committed to a RocksDB store and ACME is purged
- **THEN** before the store is closed, no file in its directory contains ACME's ID or its LEI `5493001KJTIIGC8Y1R12`

#### Scenario: A purge interrupted in a RocksDB store

- **WHEN** a RocksDB store that holds Alice in its data files writes a purge of Alice and stops before it has compacted its files, and the store is opened
- **THEN** the store has no Alice, and no file in its directory contains the text `Alice`

### Requirement: Purging by unique key

A purge MAY name entities by unique key (see the `transactions` capability) as well as by ID, and a key MAY come with an effective time. THE SYSTEM SHALL check a key as it checks keys in writes, and SHALL resolve it against the store at the latest basis before the purge: without an effective time, to the one entity of the key's type, or of a subtype, that holds the value at some effective time; with one, to the entity that holds the value at that time. WHEN a key is invalid, names no entity, or, without an effective time, names several entities that held the value at different times, THE SYSTEM SHALL reject the purge with a problem at the key (`@purge.key.` followed by the key's position among the purge's keys, from 0), SHALL change nothing, and SHALL use no basis; a problem about several entities SHALL name each of them with the periods in which it held the value. An entity that a key names SHALL be purged as if the purge named its ID. The purge SHALL return, with the IDs it removed, the entity that each key named. A purge SHALL NOT write the key or its value anywhere: not in the purge record, the change feed, or any file of the store; the value SHALL stay in the store only as data of entities that the purge does not remove.

#### Scenario: Purging ACME by its LEI

- **WHEN** the transactions of RD-1 and RD-9 are recorded, and ACME is purged by the key `Organization(lei: 5493001KJTIIGC8Y1R12)`
- **THEN** the purge returns that the key named ACME, and ACME's ID among the removed entities, no view at any basis returns ACME, and Beta and Alice are unchanged

#### Scenario: A key that names no entity to purge

- **WHEN** the transactions of RD-1 are recorded, and a purge names the key `Organization(lei: 5493001KJTIIGC8Y1R12)`
- **THEN** the purge is rejected with the problem `UNIQUE_KEY_NOT_FOUND` at the key, and ACME, Beta and Alice still exist

#### Scenario: A value that two organizations held

- **WHEN** the transactions of RD-1 and RD-9 are recorded, ACME's LEI ends on 1 March, Gamma, another organization, has the LEI `5493001KJTIIGC8Y1R12` from 1 March, and a purge names that key without an effective time
- **THEN** the purge is rejected with the problem `UNIQUE_KEY_AMBIGUOUS` at the key, which names ACME until 1 March and Gamma from 1 March, and both still exist

#### Scenario: A key with an effective time

- **WHEN** ACME held the LEI `5493001KJTIIGC8Y1R12` until 1 March and Gamma holds it from 1 March, as in the previous scenario, and a purge names that key effective 1 April
- **THEN** the purge removes Gamma, and ACME is unchanged

#### Scenario: No trace of a key in a store's files

- **WHEN** the transactions of RD-1 and RD-9 are committed to a durable store kept in a directory, and to a RocksDB store, ACME is purged by the key `Organization(lei: 5493001KJTIIGC8Y1R12)`, and each store is closed and opened again
- **THEN** no file in either directory contains the LEI `5493001KJTIIGC8Y1R12`, and each store lists a purge record of one entity
