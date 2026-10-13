# Spec Delta

## Purpose

Defines rules written in Java: how a store gets them, how they run at commit, and how they report problems.

## ADDED Requirements

### Requirement: Rules in Java

A rule SHALL be a Java object that describes itself, with the name, version, description, entity type and problem codes of an available rule, and that checks one entity version. A store SHALL be created with its rules, and their descriptions SHALL be the rules available to the store's schema registry. THE SYSTEM SHALL also find rules provided through Java's service loader when the store is created to do so. Two rules with the same name and version MUST NOT be given to one store. A store MAY also declare rules whose code it does not have: schemas may name them, but a write that would need to run one SHALL be rejected with a problem that names the rule.

#### Scenario: Rules become available to the schema

- **WHEN** a store is created with the rule `lei-checksum` version 1, and the reference domain model, which names it, is registered
- **THEN** the registration is applied, and the store's schema registry lists `lei-checksum` among its available rules

#### Scenario: A declared rule without code

- **WHEN** a store declares the rule `name-not-blank` for Party without its code, Party names it, and a transaction creates Alice
- **THEN** the transaction is rejected with a problem that names the rule `name-not-blank`

### Requirement: Rules run at commit

WHEN a transaction would change the values of an entity whose type, or one of whose supertypes, names a rule, THE SYSTEM SHALL run the rule for every version whose values the transaction changes, after the declarative constraints have passed for that entity. A write that only ends an entity changes no values, so it runs no rules. The rule SHALL see the version's values and effective period, and SHALL be able to read other entities through a rule context, as they would be after the transaction, at the start of the version's effective period. A rule MUST NOT be able to write.
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

A problem reported by a rule SHALL have the code for rule violations, the path of the entity or of one of its attributes, a message, and the rule's own code qualified by the rule's name, such as `lei-checksum/invalid-check-digits`. A rule MUST report only codes it declares. WHEN a rule throws an exception or reports a code it does not declare, THE SYSTEM SHALL reject the transaction with a problem that names the rule.

#### Scenario: Rule code in the problem

- **WHEN** the rule `lei-checksum` rejects Gamma's LEI
- **THEN** the problem has the rule code `lei-checksum/invalid-check-digits`

#### Scenario: Failing rule

- **WHEN** a rule throws an exception while checking an entity
- **THEN** the transaction is rejected with a problem that names the rule, and the store is unchanged
