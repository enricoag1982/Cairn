# entity-rules Specification

## Purpose
Defines rules written in Java: how a store gets them, how they run at commit, and how they report problems.

## Requirements

### Requirement: Rules in Java

A rule SHALL be a Java object that describes itself, with the name, version, description, type and problem codes of an available rule, and that checks one version of an entity or of a relationship: a rule for an entity type checks entity versions, and a rule for a relationship type checks relationship versions. A store SHALL be created with its rules, and their descriptions SHALL be among the rules available to the store's schema registry. THE SYSTEM SHALL also find rules provided through Java's service loader when the store is created to do so. Two rules with the same name and version MUST NOT be given to one store. A store MAY also declare rules whose code it does not have (*declared rules*): schemas may name them, but a transaction that writes an entity or a relationship whose type, or one of whose supertypes, names one SHALL be rejected with a problem that names the rule. A rule that a type names but that does not check versions of that kind SHALL reject the first transaction that changes the values of such an entity or relationship, with a problem that names the rule.

#### Scenario: Rules become available to the schema

- **WHEN** a store is created with the rule `lei-checksum` version 1 and declares `total-ownership-at-most-100` version 1, and the reference domain model, which names both, is registered
- **THEN** the registration is applied, and the store's schema registry lists `lei-checksum` among its available rules

#### Scenario: A declared rule without code

- **WHEN** a store declares the rule `name-not-blank` for Party without its code, Party names it, and a transaction creates Alice
- **THEN** the transaction is rejected with a problem that names the rule `name-not-blank`

### Requirement: Rules run at commit

WHEN a transaction would change the values of an entity or relationship whose type, or one of whose supertypes, names a rule, THE SYSTEM SHALL run the rule for every version whose values the transaction changes, after the declarative constraints and the integrity checks have passed for it. A write that only ends an entity or relationship changes no values, so it runs no rules. A rule's verdict MUST therefore hold at each instant of the version's period and MUST NOT depend on where that period starts or ends. A rule that needs the current time SHALL use the transaction time of the version it checks, never a clock. The rule SHALL see the version's values and effective period, and SHALL be able to read other entities and relationships, and navigate between them, through a rule context, as they would be after the transaction, at the start of the version's effective period or at any other effective time. A rule MUST NOT be able to write: a write attempted while rules run SHALL fail, and the rule SHALL be reported as failed.
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

A rule MAY apply to a relationship type. THE SYSTEM SHALL run it, as it runs rules on entities, for every version of a relationship of that type or of its subtypes whose values the transaction changes, and for the relationships that a transaction relates. A rule whose verdict depends on other relationships, such as a sum over the relationships of one entity, SHALL be named by the relationship type whose writes can break it, and SHALL use its rule context to check every instant of the version's period at which those relationships change.

#### Scenario: Owners holding more than 100%

- **WHEN** ACME owns 60% of Beta from 1 February, and a transaction recorded on 1 April creates Gamma and relates it to Beta with Owns, with the share 50, from 1 April (RD-13)
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`

#### Scenario: Shares that change later in the period

- **WHEN** ACME owns 40% of Beta from 1 February, Gamma owns 50% of Beta from 1 April, and a transaction patches ACME's share to 55 from 1 February
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`, because from 1 April the owners would hold 105%
