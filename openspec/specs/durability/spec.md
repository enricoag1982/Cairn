# durability Specification

## Purpose
Defines durable stores: stores kept in a directory through a commit log and snapshot files, how they recover after a stop or a crash, and what holds after they are opened again.

## Requirements

### Requirement: Durable stores

THE SYSTEM SHALL open a store on a directory that keeps it on disk. A store opened on a new or empty directory SHALL start empty; a store opened on a directory that a store wrote SHALL recover that store's state. Every transaction that is committed, of data or of the schema, SHALL be on disk before its commit returns, and recovery SHALL give the state of the committed transactions: the schema and its history, every version, the transaction records, and the receipts of idempotency keys, at the same bases and transaction times. Dry runs and rejected transactions SHALL leave nothing to recover. In every other way, a durable store SHALL behave as an in-memory store.

#### Scenario: Reopening a store

- **WHEN** the reference domain model is registered and the transactions of RD-1 to RD-5 are committed to a durable store, which is closed and opened again
- **THEN** every view of the reopened store, at every basis and effective time the stories use, reads what the same view of the first store read, and the schema's history is the same

#### Scenario: The next commit after reopening

- **WHEN** a durable store whose latest basis is 7 is closed and opened again, and a transaction is committed
- **THEN** the transaction gets the basis 8

#### Scenario: An idempotency key after reopening

- **WHEN** a transaction with an idempotency key is committed, the store is closed and opened again, and the same transaction is committed again
- **THEN** the receipt of the first commit is returned, and nothing is recorded

### Requirement: Recovery repeats what was committed

Recovery SHALL apply what each recorded transaction did. It SHALL NOT validate, run rules, or use the store's clock or ID generator. After recovery, before the store is returned, THE SYSTEM SHALL run each rule it has code for over every version of every entity and relationship whose type names it, only to record its reads (see the `entity-rules` capability): what the rules report or throw SHALL NOT be reported and SHALL NOT stop the store from opening, unless the virtual machine runs out of memory or fails. After recovery, transaction times SHALL keep increasing from the last recorded one, and a new entity or relationship SHALL NOT get an ID that the store already has. A store keeps the ID generator chosen when it was created: opening it with a generator that does not accept the IDs it holds SHALL fail with an exception.

#### Scenario: Recovering without the code of a rule

- **WHEN** a durable store with the rule `lei-checksum` records RD-9's LEI of ACME, and is opened again with `lei-checksum` declared without its code
- **THEN** ACME's LEI reads as before, and a transaction that changes ACME is rejected with a problem that names the rule

#### Scenario: A clock that went back

- **WHEN** a durable store is opened again with a clock set before its last transaction time, and a transaction is committed
- **THEN** the transaction time of the commit is after the last recorded one

#### Scenario: Another ID generator

- **WHEN** a durable store created with the default ID generator, which holds entities and relationships, is opened again with an ID generator of another format, which does not accept their IDs
- **THEN** opening fails with an exception

#### Scenario: IDs after reopening

- **WHEN** entities are created in a durable store with the deterministic ID generator, the store is opened again with a new deterministic ID generator, and an entity is created
- **THEN** the new entity's ID is not the ID of any entity created before

#### Scenario: Re-checks after reopening

- **WHEN** a durable store whose Employs names `employer-holds-account` records RD-3 and RD-5, is closed and opened again, and a transaction ends A-1's HeldBy of Beta and relates A-1 to ACME with HeldBy, both from 1 June
- **THEN** the transaction is rejected with a problem from the rule `employer-holds-account`, as it would have been before the store was closed

### Requirement: Crash safety

Recovery SHALL drop what a crash during a commit can leave at the end of the log, and open with every transaction before it: a last record that is cut short, or whose content fails its checksum with nothing but zero bytes after it, and a last log segment that holds nothing but zero bytes. Such a record may be one whose commit had returned, if only that record was damaged later; this cannot be told from a crash. Recovery SHALL refuse to open a store whose log is damaged anywhere else, or whose files were written in a format version it does not know, with an exception that names the file, and SHALL change nothing on disk then.

#### Scenario: A commit cut short

- **WHEN** the last record of a durable store's log is cut short, and the store is opened
- **THEN** the store has every transaction before that record, and the next commit gets that record's basis

#### Scenario: A last record full of zeros

- **WHEN** the content of the last record of a durable store's log is zero bytes from some point on, as a file system that extends a file before writing its data can leave it after a crash, and the store is opened
- **THEN** the store has every transaction before that record

#### Scenario: A damaged log

- **WHEN** a record before the last one in a durable store's log is damaged, and the store is opened
- **THEN** opening fails with an exception that names the file and the position of the damage, and the files are unchanged

#### Scenario: An unknown format version

- **WHEN** a durable store's log was written in a format version that this version of Cairn does not know, and the store is opened
- **THEN** opening fails with an exception that names the file and the version

### Requirement: Snapshot files

A durable store SHALL write a snapshot file of its latest state when asked and when it is closed, without stopping commits while it writes. Recovery SHALL start from the newest snapshot file that is complete and undamaged, and apply the log after it. A durable store SHALL keep the two newest snapshot files and the log needed to recover from the older of them, and MAY delete older snapshot files and log; a purge deletes every snapshot file but the one it writes (capability `purge`), so until the next snapshot file is written a store has one. WHEN no snapshot file is valid and the log does not start at the first basis, THE SYSTEM SHALL refuse to open the store with an exception that names the files, and SHALL change nothing on disk, rather than open a store that lacks what the deleted files held.

#### Scenario: Recovering from a snapshot file and the log after it

- **WHEN** the transactions of RD-1 and RD-2 are committed to a durable store, a snapshot file is written, the transaction of RD-3 is committed, and the store is opened again without being closed
- **THEN** the reopened store reads as the first one did, including RD-3

#### Scenario: A damaged newest snapshot file

- **WHEN** a durable store wrote two snapshot files, the newest is damaged, and the store is opened
- **THEN** the store recovers from the older snapshot file and the log after it, with every committed transaction

#### Scenario: A damaged snapshot file after a purge

- **WHEN** a durable store purged Alice, its only snapshot file is damaged, and the store is opened
- **THEN** opening fails with an exception that names the snapshot file, and the files are unchanged

### Requirement: One store per directory

A directory SHALL be held by one open durable store at a time, in any process. Opening a store on a directory that another open store holds SHALL fail with an exception. Closing a store SHALL release the directory; a closed store SHALL refuse every further call but closing it again, which does nothing.

#### Scenario: A directory in use

- **WHEN** a durable store is open on a directory, and a second store is opened on the same directory
- **THEN** opening the second store fails with an exception, and after the first store is closed, opening succeeds

### Requirement: A commit that cannot be written

WHEN writing a commit to disk fails, THE SYSTEM SHALL NOT apply the transaction, SHALL report the failure with an exception, and SHALL refuse further commits and schema registrations until the store is opened again. Reads SHALL go on. If the record of the failed commit reached the disk whole, recovery MAY apply it when the store is opened again, so a caller that retries the transaction should give it an idempotency key.

#### Scenario: A failed write

- **WHEN** writing to the log fails while a transaction is committed
- **THEN** the commit fails with an exception, the transaction is not visible, a further commit is refused, and views still read the earlier state
