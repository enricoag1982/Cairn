# Spec Delta

## Purpose

Defines relationships between entities: relating, integrity at both ends at every point in effective time, compositions, symmetric types, navigation and traversal.

## ADDED Requirements

### Requirement: Relating entities

A write SHALL relate a source entity to a target entity with a relationship type, for an effective period, with attribute values. A relationship SHALL have its own ID, from the store's generator or supplied by the caller (see the `transactions` capability), MAY be named with a temporary ID, and its type and ends SHALL never change. A problem about a relationship SHALL name it as problems about entities do: by its temporary ID, by its ID, or, for a relationship related without a temporary or supplied ID, by the position of the write that relates it (`@write.` and the position). The source MUST be an entity of the relationship type's source type or one of its subtypes, and the target an entity of its target type or one of its subtypes. The ends MAY be given by entity IDs or by temporary IDs of entities created in the same transaction.
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

### Requirement: Relationships stay within the lifetimes of their ends

At every point of a relationship's effective period, both of its ends SHALL exist. THE SYSTEM SHALL reject a transaction that relates entities outside their lifetimes, or that ends an entity while relationships to it continue after that instant, unless the same transaction also ends those relationships.

#### Scenario: Ending an entity with continuing relationships

- **WHEN** account A-1 is held by Beta, and a transaction ends Beta from 1 June without ending A-1's HeldBy (RD-8)
- **THEN** the transaction is rejected with a problem about the HeldBy relationship

#### Scenario: Ending an entity together with its relationships

- **WHEN** a transaction ends Beta from 1 June, and from the same instant ends HeldBy between A-1 and Beta and relates A-1 to ACME with HeldBy, ends the Owns and Employs relationships involving Beta, and ends Beta's customer role together with its PlayedBy (RD-8)
- **THEN** the transaction is committed

#### Scenario: Relationship before an end exists

- **WHEN** Alice exists from 1 January, and a transaction relates Beta to Alice with Employs from 1 December of the previous year
- **THEN** the transaction is rejected with a problem about the relationship

### Requirement: Multiplicities hold at every point in effective time

At every point in effective time, an entity of a relationship type's source type SHALL have at least the minimum and at most the maximum number of targets per source, and an entity of its target type at least the minimum and at most the maximum number of sources per target, counting relationships of the type and its subtypes. A minimum of 1 SHALL apply to every point of the entity's lifetime. A problem about a multiplicity SHALL name the entity followed, in brackets, by the relationship type's name for the source end or its inverse name for the target end, such as `$a3[HeldBy]`, and by the type's name for a symmetric type. Entities that a transaction touches only as ends of relationships SHALL be checked for their multiplicities and lifetimes, but not validated again.

#### Scenario: Account without a holder

- **WHEN** a transaction creates account A-3 without relating it to a party with HeldBy
- **THEN** the transaction is rejected with a problem about A-3 and HeldBy

#### Scenario: Account with two holders

- **WHEN** A-1 is held by Beta, and a transaction relates A-1 to ACME with HeldBy from 1 June without ending the first
- **THEN** the transaction is rejected with a problem about A-1 and HeldBy

#### Scenario: Moving an account to another holder

- **WHEN** a transaction ends A-1's HeldBy to Beta from 1 June and relates A-1 to ACME with HeldBy from 1 June
- **THEN** the transaction is committed, and A-1 has exactly one holder at every point in effective time

### Requirement: No duplicate relationships

THE SYSTEM SHALL reject a transaction that would give two relationships of the same type the same source and target at the same point in effective time.

#### Scenario: Owning the same organization twice

- **WHEN** ACME owns 55% of Beta from 1 February, and a transaction relates ACME to Beta with Owns again from 1 March
- **THEN** the transaction is rejected with a problem about the second relationship

### Requirement: Compositions end with their source

WHEN an entity that is the source of a composition ends, THE SYSTEM SHALL end, from the same instant and in the same transaction, the composition's relationships from that entity that started before that instant and continue past it, and their targets, and so on for compositions of those targets. The receipt SHALL list what the cascade ended (see the `transactions` capability).

#### Scenario: Addresses end with their party

- **WHEN** Beta has an address through HasAddress, and a transaction ends Beta and its other relationships from 1 June
- **THEN** the transaction is committed, and a view effective at 1 June finds neither Beta nor its address

### Requirement: Symmetric relationship types

For a symmetric relationship type, THE SYSTEM SHALL treat a relationship between two entities as the same, whichever of them is given as the source. Navigation by the type's name SHALL find it from both ends.

#### Scenario: Related parties

- **WHEN** the symmetric relationship type RelatedParty relates Party to Party, ACME is related to Beta, and a transaction relates Beta to ACME with RelatedParty at the same time
- **THEN** the transaction is rejected as a duplicate
- **AND** navigating RelatedParty from Beta finds ACME

### Requirement: Navigating relationships

A view SHALL navigate from an entity by a relationship type's name to the targets of the relationships it is the source of, and by the type's inverse name to the sources of the relationships it is the target of, including relationships of subtypes. Names and aliases SHALL be compared without regard to case; for a symmetric type, its name and its inverse name lead from both ends. Navigating by a name that the view's schema does not have SHALL fail. Each result SHALL hold the relationship's state and the state of the entity at the other end, at the view's coordinates, ordered by relationship ID. A view SHALL also read a relationship by its ID, and its timeline. A view SHALL also return, for an entity and a name, every version at any effective time of the relationships that navigation by that name would find at some effective time, as known at its basis, ordered by relationship and then by the start of the version's period, so that history across effective time can be read without knowing relationship IDs.

#### Scenario: Who owns Beta

- **WHEN** the transactions of RD-2 are recorded, and Beta's `ownedBy` is navigated through a view effective at 1 March
- **THEN** the result is ACME, through an Owns relationship with the share 55

#### Scenario: Where Alice works

- **WHEN** the transaction of RD-3 is recorded, and Alice's `employedBy` is navigated through a view effective at 1 April
- **THEN** the result is Beta, through an Employs relationship with the title "Analyst"

#### Scenario: A role that has ended

- **WHEN** Beta is a customer, segment "Corporate", from 1 March until 1 October (RD-4), and Beta's `roles` is navigated through views effective at 1 April and at 1 November
- **THEN** the first finds Beta's CustomerRole, and the second finds nothing

#### Scenario: Every owner of Beta over time

- **WHEN** ACME owns 40% of Beta from 1 February and Gamma owns 50% of Beta from 1 April, and the ownership history of Beta's `ownedBy` is read through a view
- **THEN** it lists ACME's Owns relationship from 1 February and Gamma's from 1 April, with their shares

### Requirement: Traversing relationships

A view SHALL traverse relationships recursively from an entity by a relationship type's name or inverse name, following only relationships that satisfy a condition, up to a maximum depth. The traversal SHALL be breadth first. Each entity SHALL be visited at most once, through the first relationship that reaches it, and the start entity is never part of the result, so cycles end the traversal. A maximum depth of 0 SHALL give nothing, and a negative one SHALL fail. The result SHALL list each reached entity with its depth and the relationship it was reached through.

#### Scenario: Ultimate parent

- **WHEN** ACME owns 55% of Beta, and the traversal from Beta follows `ownedBy` while the share is above 50, with a maximum depth of 10
- **THEN** the traversal reaches ACME at depth 1, which is Beta's ultimate parent

#### Scenario: Ownership cycle

- **WHEN** ACME owns 60% of Beta and Beta owns 60% of ACME, and the traversal from Beta follows `ownedBy` while the share is above 50
- **THEN** the traversal reaches ACME at depth 1 and stops
