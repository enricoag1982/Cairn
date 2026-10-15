# entity-rules Specification

## Purpose
Defines rules written in Java: how a store gets them, how they run at commit, and how they report problems.

## Requirements

### Requirement: Rules in Java

A rule SHALL be a Java object that describes itself, with the name, version, description, type and problem codes of an available rule, and that checks one version of an entity or of a relationship: a rule for an entity type checks entity versions, and a rule for a relationship type checks relationship versions. A store SHALL be created with its rules, and their descriptions SHALL be among the rules available to the store's schema registry. THE SYSTEM SHALL also find rules provided through Java's service loader when the store is created to do so. Two rules with the same name and version MUST NOT be given to one store. A store MAY also declare rules whose code it does not have (*declared rules*): schemas may name them, but a transaction that writes an entity or a relationship whose type, or one of whose supertypes, names one SHALL be rejected with a problem that names the rule. A store SHALL list its declared rules, so that tools can tell which available rules have code. A rule that a type names but that does not check versions of that kind SHALL reject the first transaction that changes the values of such an entity or relationship, with a problem that names the rule.

#### Scenario: Rules become available to the schema

- **WHEN** a store is created with the rule `lei-checksum` version 1 and declares `total-ownership-at-most-100` version 1, and the reference domain model, which names both, is registered
- **THEN** the registration is applied, and the store's schema registry lists `lei-checksum` among its available rules

#### Scenario: A declared rule without code

- **WHEN** a store declares the rule `name-not-blank` for Party without its code, Party names it, and a transaction creates Alice
- **THEN** the transaction is rejected with a problem that names the rule `name-not-blank`

#### Scenario: Listing declared rules

- **WHEN** a store is created with the rule `lei-checksum` version 1 and declares `total-ownership-at-most-100` version 1
- **THEN** the store lists `total-ownership-at-most-100` as its only declared rule, and its schema registry lists both rules as available

### Requirement: Rules run at commit

WHEN a transaction would change the values of an entity or relationship whose type, or one of whose supertypes, names a rule, THE SYSTEM SHALL run the rule for every version whose values the transaction changes, after the declarative constraints and the integrity checks have passed for it. A write that only ends an entity or relationship changes no values, so it runs none of its rules, though it can run rules again for what read it. A rule's verdict MUST therefore hold at each instant of the version's period and MUST NOT depend on where that period starts or ends. A rule that needs the current time SHALL use the transaction time of the version it checks, never a clock. The rule SHALL see the version's values and effective period, and SHALL be able to read other entities and relationships, and navigate between them, through a rule context, as they would be after the transaction, at the start of the version's effective period or at any other effective time. A rule MUST NOT be able to write: a write attempted while rules run SHALL fail, and the rule SHALL be reported as failed.
WHEN a rule reports a problem, THE SYSTEM SHALL reject the transaction. Dry runs SHALL run rules as commits do.

#### Scenario: Invalid LEI check digits

- **WHEN** a transaction creates Gamma, an Organization with the LEI `5493001KJTIIGC8Y1R13`, whose check digits are wrong (RD-12)
- **THEN** the transaction is rejected with a problem from the rule `lei-checksum`, about the attribute `lei` of Gamma

#### Scenario: Valid LEI

- **WHEN** a transaction creates ACME with the LEI `5493001KJTIIGC8Y1R12`
- **THEN** the transaction is committed

#### Scenario: Rule named by a supertype

- **WHEN** the rule `name-not-blank` is named by Party and a transaction creates the person Alice with the name "  "
- **THEN** the transaction is rejected with a problem from the rule `name-not-blank`

#### Scenario: Dry run runs rules

- **WHEN** the transaction that creates Gamma with wrong check digits is run dry
- **THEN** the dry run reports the problem from the rule `lei-checksum`

### Requirement: Rule problems

A problem reported by a rule SHALL have the code for rule violations, the path of the entity or relationship or of one of its attributes, a message, and the rule's own code qualified by the rule's name (`<rule name>/<code>`). A rule MUST report only codes it declares, and only attributes of the type of the entity or relationship it checks. A problem reported for several versions of one entity or relationship SHALL be reported once. WHEN a rule throws an exception or an error other than the virtual machine running out of resources, reports a code it does not declare, or names an attribute that the entity or relationship does not have, THE SYSTEM SHALL reject the transaction with a problem that names the rule.

#### Scenario: Rule code in the problem

- **WHEN** the rule `lei-checksum` rejects Gamma's LEI
- **THEN** the problem has the rule code `lei-checksum/invalid-check-digits`

#### Scenario: Failing rule

- **WHEN** a rule throws an exception while checking an entity
- **THEN** the transaction is rejected with a problem that names the rule, and the store is unchanged

#### Scenario: A rule that tries to write

- **WHEN** a rule that holds a reference to its store commits a transaction while it checks an entity
- **THEN** the outer transaction is rejected with a problem that names the rule, and the store is unchanged

#### Scenario: Ending an entity whose rule has no code

- **WHEN** a store declares the rule `name-not-blank` for Party without its code, Party names it, and a transaction only ends Alice
- **THEN** the transaction is rejected with a problem that names the rule `name-not-blank`

### Requirement: Rules on relationships

A rule MAY apply to a relationship type. THE SYSTEM SHALL run it, as it runs rules on entities, for every version of a relationship of that type or of its subtypes whose values the transaction changes, and for the relationships that a transaction relates. A rule whose verdict depends on other relationships, such as a sum over the relationships of one entity, SHALL use its rule context to check every instant of the version's period at which those relationships change. It need not be named by the types of those relationships: a transaction that changes what it read runs it again.

#### Scenario: Owners holding more than 100%

- **WHEN** ACME owns 60% of Beta from 1 February, and a transaction recorded on 1 April creates Gamma and relates it to Beta with Owns, with the share 50, from 1 April (RD-13)
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`

#### Scenario: Shares that change later in the period

- **WHEN** ACME owns 40% of Beta from 1 February, Gamma owns 50% of Beta from 1 April, and a transaction patches ACME's share to 55 from 1 February
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`, because from 1 April the owners would hold 105%

### Requirement: Rules run again when what they read changes

WHILE a rule checks a version of an entity or relationship, THE SYSTEM SHALL record what the rule reads through its rule context (its *rule reads*): an entity, a relationship, the relationships that navigation by one name finds from one entity, and the entities of an entity type, each at an effective time, or across effective time for timelines. Every entity and relationship state that a read returns SHALL count as a read of it, and traversals and queries SHALL count as the reads they are made of. Each check of a version SHALL replace the reads recorded for that version before. Rules without code record no reads.
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
