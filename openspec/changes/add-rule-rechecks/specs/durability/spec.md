## MODIFIED Requirements

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
