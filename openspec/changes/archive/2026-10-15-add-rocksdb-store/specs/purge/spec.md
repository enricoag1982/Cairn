## MODIFIED Requirements

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
