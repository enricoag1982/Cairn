## MODIFIED Requirements

### Requirement: Relationships stay within the lifetimes of their ends

At every point of a relationship's effective period, both of its ends SHALL exist, except the target of a relationship whose type outlives its targets: that target SHALL exist at the start of each stretch of the relationship's effective period, and the relationship MAY continue after the target ends, as a record of a past fact. THE SYSTEM SHALL reject a transaction that relates entities outside their lifetimes, or that ends an entity while relationships to it continue after that instant, unless the same transaction also ends those relationships or their type outlives that entity as their target.

#### Scenario: Ending an entity with continuing relationships

- **WHEN** account A-1 is held by Beta, and a transaction ends Beta from 1 June without ending A-1's HeldBy (RD-8)
- **THEN** the transaction is rejected with a problem about the HeldBy relationship

#### Scenario: Ending an entity together with its relationships

- **WHEN** a transaction ends Beta from 1 June, and from the same instant ends HeldBy between A-1 and Beta and relates A-1 to ACME with HeldBy, ends the Owns and Employs relationships involving Beta, and ends Beta's customer role together with its PlayedBy (RD-8)
- **THEN** the transaction is committed

#### Scenario: Relationship before an end exists

- **WHEN** Alice exists from 1 January, and a transaction relates Beta to Alice with Employs from 1 December of the previous year
- **THEN** the transaction is rejected with a problem about the relationship

#### Scenario: Ending the target of a relationship that outlives it

- **WHEN** the reference model has the test-only association OpenedBy from Account to Person, which outlives its targets, the transactions of RD-1 and RD-5 are recorded, A-1 is opened by Alice through OpenedBy from 1 January, and a transaction ends Alice from 1 June without ending the OpenedBy
- **THEN** the transaction is committed, and A-1's OpenedBy is still effective on 1 July

#### Scenario: A relationship that starts after its target ended

- **WHEN** Alice has ended on 1 June, as in the previous scenario, and a transaction relates A-2 to Alice with OpenedBy from 1 July
- **THEN** the transaction is rejected with a problem about the relationship

### Requirement: Multiplicities hold at every point in effective time

At every point in effective time, an entity of a relationship type's source type SHALL have at least the minimum and at most the maximum number of targets per source, and an entity of its target type at least the minimum and at most the maximum number of sources per target, counting relationships of the type and its subtypes. A minimum of 1 SHALL apply to every point of the entity's lifetime. For a relationship type that outlives its targets, a relationship SHALL count at its source for as long as it is effective, and at its target only while the target exists. A problem about a multiplicity SHALL name the entity followed, in brackets, by the relationship type's name for the source end or its inverse name for the target end, such as `$a3[HeldBy]`, and by the type's name for a symmetric type. Entities that a transaction touches only as ends of relationships SHALL be checked for their multiplicities and lifetimes, but not validated again.

#### Scenario: Account without a holder

- **WHEN** a transaction creates account A-3 without relating it to a party with HeldBy
- **THEN** the transaction is rejected with a problem about A-3 and HeldBy

#### Scenario: Account with two holders

- **WHEN** A-1 is held by Beta, and a transaction relates A-1 to ACME with HeldBy from 1 June without ending the first
- **THEN** the transaction is rejected with a problem about A-1 and HeldBy

#### Scenario: Moving an account to another holder

- **WHEN** a transaction ends A-1's HeldBy to Beta from 1 June and relates A-1 to ACME with HeldBy from 1 June
- **THEN** the transaction is committed, and A-1 has exactly one holder at every point in effective time

#### Scenario: A record still counts at its source

- **WHEN** A-1 is opened by Alice through OpenedBy, which has at most one target per source, Alice has ended on 1 June, and a transaction creates Dana, a person, from 1 July, and relates A-1 to Dana with OpenedBy from 1 July
- **THEN** the transaction is rejected with a problem about A-1 and OpenedBy

### Requirement: Compositions end with their source

WHEN an entity that is the source of a composition ends, THE SYSTEM SHALL end, from the same instant and in the same transaction, the composition's relationships from that entity that started before that instant and continue past it, and their targets, and so on for compositions of those targets. Because a target ends with its source, THE SYSTEM SHALL also end, from the same instant, every other relationship of each target that the cascade ends, of any type and at either end, that started before that instant and continues past it, including one that the transaction itself relates or ends later, except a relationship whose type outlives its targets, whose target is that entity and whose source is another; the entities at the other ends are checked for their multiplicities as for any relationship a transaction ends. An entity that a write ends from that instant or earlier is not a target: its other relationships, except those with a target, stay the writer's to end. A relationship that a cascade ends counts as written by the transaction, as one that a write ends does. The receipt SHALL list what the cascade ended (see the `transactions` capability): for each composition relationship in the order of its ID, the relationship, its target, what the cascade ends from that target, depth first, and then the target's other relationships, in the order of their IDs.

#### Scenario: Addresses end with their party

- **WHEN** Beta has an address through HasAddress, and a transaction ends Beta and its other relationships from 1 June
- **THEN** the transaction is committed, and a view effective at 1 June finds neither Beta nor its address

#### Scenario: The relationships of a target end with it

- **WHEN** the reference model has the test-only association `MailedTo` from Account to Address, with 0..1 targets per source and 0..* sources per target, Beta has an address through HasAddress, A-1's statements are mailed to it, and a transaction, from 1 June, ends Beta and moves A-1 to ACME (ends A-1's HeldBy of Beta and relates A-1 to ACME with HeldBy, as in RD-8)
- **THEN** the transaction is committed, A-1's MailedTo ends from 1 June, and the receipt lists it after Beta's address

#### Scenario: A cascade that leaves another entity without a required relationship

- **WHEN** `MailedTo` has 1..1 targets per source instead, and the same transaction is run
- **THEN** the transaction is rejected with a problem about A-1 and MailedTo

#### Scenario: A cascade leaves a relationship that outlives its target

- **WHEN** `MailedTo` outlives its targets instead, and the transaction of "The relationships of a target end with it" is run
- **THEN** the transaction is committed, A-1's MailedTo is still effective after 1 June, and the receipt does not list it

### Requirement: Navigating relationships

A view SHALL navigate from an entity by a relationship type's name to the targets of the relationships it is the source of, and by the type's inverse name to the sources of the relationships it is the target of, including relationships of subtypes. Names and aliases SHALL be compared without regard to case; for a symmetric type, its name and its inverse name lead from both ends. Navigating by a name that the view's schema does not have SHALL fail. Each result SHALL hold the relationship's state and the state of the entity at the other end, at the view's coordinates, ordered by relationship ID. For a relationship whose type outlives its targets and whose target does not exist at the view's effective time, the result SHALL hold the target's last version as known at the view's basis, whose period ends at or before the view's effective time; queries that follow such a relationship SHALL bind the same version (see the `queries` capability). A view SHALL also read a relationship by its ID, and its timeline. A view SHALL also return, for an entity and a name, every version at any effective time of the relationships that navigation by that name would find at some effective time, as known at its basis, ordered by relationship and then by the start of the version's period, so that history across effective time can be read without knowing relationship IDs.

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

#### Scenario: Who opened A-1 after Alice ended

- **WHEN** A-1 is opened by Alice through OpenedBy from 1 January, Alice has ended on 1 June, and A-1's `OpenedBy` is navigated through a view effective at 1 July
- **THEN** the result is Alice's last version, whose period ends on 1 June, through the OpenedBy relationship

#### Scenario: A query that follows a relationship to an ended target

- **WHEN** A-1 is opened by Alice as in the previous scenario, and a query for Accounts follows `OpenedBy`, binding the opener, effective 1 July
- **THEN** the row for A-1 binds Alice's last version, with her name

### Requirement: Traversing relationships

A view SHALL traverse relationships recursively from an entity by a relationship type's name or inverse name, following only relationships that satisfy a condition, when one is given, up to a maximum depth, when one is given, and otherwise to the end. The traversal SHALL be breadth first. Each entity SHALL be visited at most once, through the first relationship that reaches it, and the start entity is never part of the result, so cycles end the traversal. A maximum depth of 0 SHALL give nothing, and a negative one SHALL fail. The result SHALL list each reached entity with its depth and the relationship it was reached through. A traversal SHALL reach the target of a relationship that outlives it, as navigation does, and SHALL go no further from an entity that does not exist at the view's effective time.

#### Scenario: Ultimate parent

- **WHEN** ACME owns 55% of Beta, and the traversal from Beta follows `ownedBy` while the share is above 50, with a maximum depth of 10
- **THEN** the traversal reaches ACME at depth 1, which is Beta's ultimate parent

#### Scenario: Ownership cycle

- **WHEN** ACME owns 60% of Beta and Beta owns 60% of ACME, and the traversal from Beta follows `ownedBy` while the share is above 50
- **THEN** the traversal reaches ACME at depth 1 and stops

#### Scenario: A traversal to the end

- **WHEN** ACME owns 55% of Beta and Gamma owns 70% of ACME, and the traversal from Beta follows `ownedBy` without a condition or a maximum depth
- **THEN** the traversal reaches ACME at depth 1 and Gamma at depth 2

#### Scenario: A traversal that reaches an ended target

- **WHEN** A-1 is opened by Alice through OpenedBy from 1 January, Alice has ended on 1 June, and the traversal from A-1 follows `OpenedBy` effective 1 July
- **THEN** the traversal reaches Alice's last version at depth 1
