## MODIFIED Requirements

### Requirement: Compositions end with their source

WHEN an entity that is the source of a composition ends, THE SYSTEM SHALL end, from the same instant and in the same transaction, the composition's relationships from that entity that started before that instant and continue past it, and their targets, and so on for compositions of those targets. Because a target ends with its source, THE SYSTEM SHALL also end, from the same instant, every other relationship of each target that the cascade ends, of any type and at either end, that started before that instant and continues past it, including one that the transaction itself ends later; the entities at the other ends are checked for their multiplicities as for any relationship a transaction ends. An entity that a write ends is not a target: its own relationships stay the writer's to end. The receipt SHALL list what the cascade ended (see the `transactions` capability): for each composition relationship in the order of its ID, the relationship, its target, what the cascade ends from that target, depth first, and then the target's other relationships, in the order of their IDs.

#### Scenario: Addresses end with their party

- **WHEN** Beta has an address through HasAddress, and a transaction ends Beta and its other relationships from 1 June
- **THEN** the transaction is committed, and a view effective at 1 June finds neither Beta nor its address

#### Scenario: The relationships of a target end with it

- **WHEN** the reference model has the test-only association `MailedTo` from Account to Address, with 0..1 targets per source and 0..* sources per target, Beta has an address through HasAddress, A-1's statements are mailed to it, and a transaction ends Beta and its own relationships from 1 June
- **THEN** the transaction is committed, A-1's MailedTo ends from 1 June, and the receipt lists it after Beta's address

#### Scenario: A cascade that leaves another entity without a required relationship

- **WHEN** `MailedTo` has 1..1 targets per source instead, and the same transaction is run
- **THEN** the transaction is rejected with a problem about A-1 and MailedTo
