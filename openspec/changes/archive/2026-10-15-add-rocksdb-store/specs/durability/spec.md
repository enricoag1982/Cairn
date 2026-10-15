## ADDED Requirements

### Requirement: Stores kept in RocksDB

THE SYSTEM SHALL open a store kept in RocksDB in a directory (a *RocksDB store*). A store opened on a new or empty directory SHALL start empty, and one opened on a directory that a RocksDB store wrote SHALL have that store's state: the schema and its history, every version, the transaction records, the receipts of idempotency keys and the purge records, at the same bases and transaction times. Every transaction that is committed SHALL be on disk before its commit returns, together with what it changes, so that a crash at any point loses no committed transaction and leaves nothing of one that did not commit. A RocksDB store SHALL behave as an in-memory store in every other capability. Of this capability's requirements for durable stores, a RocksDB store SHALL meet *Durable stores*, *Recovery repeats what was committed*, with opening in place of recovery, *One store per directory* and *A commit that cannot be written*; it keeps no commit log segments or snapshot files, so *Crash safety* and *Snapshot files* do not apply to it. Writing a snapshot SHALL make what the store holds in memory durable in its files and return the latest basis. Opening a directory whose format version this version of Cairn does not know SHALL fail with an exception that names the version. Opening a RocksDB store on a directory that a durable store kept in a directory wrote, or such a durable store on a directory that a RocksDB store wrote, SHALL fail with an exception and leave the other store's files as they are.

#### Scenario: Reopening a RocksDB store

- **WHEN** the reference domain model is registered and the transactions of RD-1 to RD-5 are committed to a RocksDB store, which is closed and opened again
- **THEN** every view of the reopened store, at every basis and effective time the stories use, reads what the same view of the first store read, the schema's history is the same, and the next commit gets the next basis

#### Scenario: A RocksDB store after a crash

- **WHEN** a process commits the transactions of RD-1 to RD-3 to a RocksDB store and stops without closing it, and the store is opened again
- **THEN** the store holds every one of those transactions

#### Scenario: The same results as an in-memory store

- **WHEN** the same random transactions, schema registrations and purges are committed to a RocksDB store and to an in-memory store
- **THEN** every view of both, at every basis, reads alike, and both give the same receipts and problems

#### Scenario: A RocksDB directory in use

- **WHEN** a RocksDB store is open on a directory, and a second store is opened on the same directory
- **THEN** opening the second store fails with an exception, and after the first store is closed, opening succeeds

#### Scenario: An unknown format version of a RocksDB store

- **WHEN** a RocksDB store's directory holds a format version that this version of Cairn does not know, and the store is opened
- **THEN** opening fails with an exception that names the version

#### Scenario: A directory of the other kind of store

- **WHEN** a durable store kept in a directory commits the transactions of RD-1 and is closed, and a RocksDB store is opened on its directory; and a RocksDB store commits them to another directory and is closed, and a durable store kept in a directory is opened on that one
- **THEN** both openings fail with an exception, and each directory then opens as the kind of store that wrote it, with the transactions of RD-1
