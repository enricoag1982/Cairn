## MODIFIED Requirements

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
