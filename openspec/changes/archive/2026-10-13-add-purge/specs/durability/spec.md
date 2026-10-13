## MODIFIED Requirements

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
