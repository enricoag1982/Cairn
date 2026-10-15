## MODIFIED Requirements

### Requirement: Rules run again when what they read changes

WHILE a rule checks a version of an entity or relationship, THE SYSTEM SHALL record what the rule reads through its rule context (its *rule reads*): an entity, a relationship, the relationships that navigation by one name finds from one entity, and the entities of an entity type, each at an effective time, or across effective time for timelines. Every entity and relationship state that a read returns SHALL count as a read of it, traversals and queries SHALL count as the reads they are made of, and finding an entity by a unique key SHALL count as a read of the entities of its type. Each check of a version SHALL replace the reads recorded for that version before. Rules without code record no reads.
WHEN a transaction would change, at an effective time that a read covers, the values or the existence of an entity or relationship that was read, the relationships that a navigation would find, or the entities that a read of a type would find, THE SYSTEM SHALL run the rule again (a *re-check*) for the versions of the entity or relationship in the period of the version whose check made the read, against the state the transaction would produce, whether or not the transaction writes that entity or relationship. Re-checks SHALL run after the rules of what the transaction writes, and only when the transaction has no problems other than problems from rules. A rule that has reported a problem about an entity or relationship that the transaction writes SHALL NOT be run again in that transaction for any other version, of that entity or relationship or of others. Schema registrations and purges SHALL NOT run re-checks. A problem from a re-check SHALL have the path of the entity or relationship it checks, which is its ID when the transaction does not name it, and a hint that says that the transaction changed what the rule read.

#### Scenario: Ending a relationship that a rule navigated

- **WHEN** Employs names the test-only rule `employer-holds-account`, Beta employs Alice from 1 March (RD-3) and holds A-1 (RD-5), and a transaction ends A-1's HeldBy of Beta and relates A-1 to ACME with HeldBy, both from 1 June
- **THEN** the transaction is rejected with a problem from the rule `employer-holds-account`, whose path is the ID of Beta's Employs of Alice

#### Scenario: Changing values that a rule read

- **WHEN** Owns names the test-only rule `controlling-owner-has-lei`, ACME has an LEI (RD-9) and owns 60% of Beta from 1 February (RD-2), and a transaction clears ACME's LEI from 1 June
- **THEN** the transaction is rejected with a problem from the rule `controlling-owner-has-lei`, whose path is the ID of ACME's Owns of Beta, with a hint that the transaction changed what the rule read

#### Scenario: One problem from a rule

- **WHEN** ACME owns 60% of Beta from 1 February, and a transaction creates Gamma and relates it to Beta with Owns, with the share 50, from 1 April (RD-13)
- **THEN** the transaction is rejected with exactly one problem, from the rule `total-ownership-at-most-100` about Gamma's Owns, though ACME's Owns of Beta no longer meets the rule either

#### Scenario: No re-checks after other problems

- **WHEN** Employs names `employer-holds-account`, Beta employs Alice from 1 March and holds A-1, and a transaction ends A-1's HeldBy of Beta from 1 June without relating A-1 to another party
- **THEN** the transaction is rejected with a problem about A-1 and HeldBy only, and with no problem from the rule `employer-holds-account`

#### Scenario: A rule that finds by a key

- **WHEN** a test rule for Employs requires the Account with the number `A-1`, which it finds by its key, to have a balance of at least 1,000, Beta employs Alice from 1 March, and a transaction patches A-1's balance to 500 from 1 March
- **THEN** the transaction is rejected with a problem from that rule about Beta's Employs of Alice
