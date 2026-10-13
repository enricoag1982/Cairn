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

The ID of a purged entity or relationship SHALL never be given to a new one, and SHALL be refused when a write supplies it. A transaction that repeats the idempotency key of a transaction that wrote purged data SHALL be rejected with a problem, so that a retry cannot record the purged data again.

#### Scenario: A purged ID supplied again

- **WHEN** Alice is purged, and a transaction creates a Person with Alice's ID as its supplied ID
- **THEN** the transaction is rejected with a problem at that write

#### Scenario: A retry after a purge

- **WHEN** the transaction of RD-1 is committed with an idempotency key, Alice is purged, and the same transaction is committed again with the same key
- **THEN** the commit is rejected with a problem about the key, and Alice does not exist

### Requirement: Purges in durable stores

A durable store SHALL make a purge durable before it returns: it SHALL write the purge to its commit log, write a snapshot file without the purged data, and delete every older snapshot file and log segment, so that no file of the store holds the purged data. If the store stops after the purge reached its log, recovery SHALL complete the purge before the store opens.

#### Scenario: Nothing left on disk

- **WHEN** the transactions of RD-1 and RD-3 are committed to a durable store, Alice is purged, and the store is closed and opened again
- **THEN** no view of the store returns Alice, and no file in its directory contains the text `Alice`

#### Scenario: A purge interrupted

- **WHEN** a durable store writes a purge of Alice to its log and stops before the purge returns, and the store is opened
- **THEN** the store has no Alice, and no file in its directory contains the text `Alice`
