## MODIFIED Requirements

### Requirement: Relating entities

A write SHALL relate a source entity to a target entity with a relationship type, for an effective period, with attribute values. A relationship SHALL have its own ID, from the store's generator or supplied by the caller (see the `transactions` capability), MAY be named with a temporary ID, and its type and ends SHALL never change. A problem about a relationship SHALL name it as problems about entities do: by its temporary ID, by its ID, or, for a relationship related without a temporary or supplied ID, by the position of the write that relates it (`@write.` and the position). The source MUST be an entity of the relationship type's source type or one of its subtypes, and the target an entity of its target type or one of its subtypes. The ends MAY be given by entity IDs, by temporary IDs of entities created in the same transaction, or by unique keys (see the `transactions` capability).
Relationship attributes SHALL be validated as entity attributes are (see the `entity-versions` capability), and relationships SHALL be patched, replaced, end-dated and corrected with the same writes as entities.

#### Scenario: An organization owns another

- **WHEN** a transaction recorded on 10 February relates ACME to Beta with Owns, with the share 60, from 1 February (RD-2)
- **THEN** the transaction is committed, and a view effective at 1 March shows the relationship with the share 60

#### Scenario: Correcting a share

- **WHEN** ACME owns 60% of Beta from 1 February, and a transaction recorded on 2 May corrects the share to 55 from 1 February (RD-2)
- **THEN** a view effective at 1 March, known at 1 April, shows the share 60, and known now shows 55

#### Scenario: Relating to a subtype

- **WHEN** a transaction relates Beta to Alice with Employs, with the title "Analyst", from 1 March (RD-3)
- **THEN** the transaction is committed, because Alice is a Person

#### Scenario: Wrong end type

- **WHEN** a transaction relates account A-1 to Beta with Owns
- **THEN** the transaction is rejected with a problem about the source of the relationship
