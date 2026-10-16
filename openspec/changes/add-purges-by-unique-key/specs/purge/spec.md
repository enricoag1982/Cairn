## ADDED Requirements

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
