# Spec Delta

## Purpose

Defines how entities are created, patched, replaced, end-dated and corrected on two time axes, and how their values are validated at commit.

## ADDED Requirements

### Requirement: Creating entities

A write SHALL create an entity of a concrete entity type, with attribute values, effective from an instant and, optionally, until a later instant. An entity's type SHALL never change. Creating an entity of an abstract type, or of a name that is not an entity type, SHALL be a problem.
Effective times SHALL be UTC instants with microsecond precision; periods SHALL be half-open, and an open end means until further notice. An effective period MUST end after it starts.

#### Scenario: Creating an organization

- **WHEN** a transaction recorded on 5 January creates ACME, an Organization named "ACME", effective from 1 January (RD-1)
- **THEN** a view effective at 1 February shows ACME with the name "ACME"
- **AND** a view effective at 31 December of the previous year does not find ACME

#### Scenario: Creating an entity of an abstract type

- **WHEN** a transaction creates an entity of type Party
- **THEN** the transaction is rejected with a problem

#### Scenario: Misspelled entity type

- **WHEN** a transaction creates an entity of type `Organisation`
- **THEN** the transaction is rejected with a problem whose hint names Organization

### Requirement: Attribute values are validated

THE SYSTEM SHALL check every entity version that a transaction would produce, at every point in effective time. Every value MUST belong to an attribute of the entity's type, named by its name or an alias, and MUST fit the attribute's value type, where an int fits a long or decimal attribute and a long fits a decimal attribute. Every value MUST satisfy the attribute's range, allowed values, pattern and lengths. Every required attribute MUST have a value. A write MUST NOT set an attribute that is deprecated. Each problem SHALL name the entity, by its temporary ID, by its ID, or, for an entity created without either, by the position of the write that creates it (`@write.` followed by the position, counted from 0), and the attribute, and SHALL state what was expected and what was found.

#### Scenario: Value of the wrong type

- **WHEN** ACME is created with the int 42 as its name
- **THEN** the transaction is rejected with a problem that expects a string and found an int

#### Scenario: Value that breaks a pattern

- **WHEN** account A-1 is created with the currency `eur`
- **THEN** the transaction is rejected with a problem about the attribute `currency` of A-1

#### Scenario: Unknown attribute

- **WHEN** ACME is created with a value for the attribute `nmae`
- **THEN** the transaction is rejected with a problem whose hint names the attribute `name`

#### Scenario: Problem about an entity without a temporary ID

- **WHEN** the second write of a transaction creates an Organization without a name, a temporary ID or a supplied ID
- **THEN** the transaction is rejected with a problem located at `@write.1.name`, in a dry run as in a commit

#### Scenario: Missing required attribute

- **WHEN** an Organization is created without a name
- **THEN** the transaction is rejected with a problem about the attribute `name`

#### Scenario: Writing a deprecated attribute

- **WHEN** the attribute `birthDate` of Person is deprecated, and a transaction sets Alice's `birthDate`
- **THEN** the transaction is rejected with a problem about the attribute `birthDate`

### Requirement: Patching entities

A patch SHALL set some attributes of an entity for an effective period and leave the others unchanged. A patch MAY clear an optional attribute. Patching a period in which the entity does not exist SHALL be a problem. Where the period overlaps earlier versions, THE SYSTEM SHALL record new versions for the overlap and keep the earlier versions readable at earlier bases.

#### Scenario: Patching a balance

- **WHEN** accounts A-1 and A-2 exist from 1 January with balances 1,000 and 2,000, and a transaction recorded on 16 March patches A-1's balance to 1,500 from 15 March (RD-5)
- **THEN** a view effective at 31 March shows A-1 with the balance 1,500 and its currency unchanged
- **AND** a view effective at 14 March shows A-1 with the balance 1,000

#### Scenario: Future-dated change

- **WHEN** a transaction recorded on 12 October patches ACME's name to "ACME Holdings" from 1 January of the next year (RD-7)
- **THEN** a view effective at 31 December shows the name "ACME", and a view effective at 1 January of the next year shows "ACME Holdings"

#### Scenario: Patching before an entity exists

- **WHEN** ACME exists from 1 January, and a transaction patches its name from 1 December of the previous year
- **THEN** the transaction is rejected with a problem

### Requirement: Replacing entity state

A replace SHALL set the complete state of an entity for an effective period: attributes it does not mention SHALL have no value in that period.

#### Scenario: Replacing a person's state

- **WHEN** Alice has a name and a birth date, and a replace from 1 June sets only her name
- **THEN** a view effective at 1 June shows Alice without a birth date, and a view effective at 31 May shows her birth date

#### Scenario: Replacing an account's state

- **WHEN** account A-1 has a number, a currency and a balance, and a replace from 1 June sets only its number and balance
- **THEN** the transaction is rejected, because the required attribute `currency` would have no value

### Requirement: End-dating entities

An end-date SHALL end an entity from an instant in effective time: from that instant, views SHALL not find it. Nothing is deleted, and earlier effective times and earlier bases SHALL still show it. Patching or replacing an end-dated entity after its end SHALL be a problem.

#### Scenario: Ending an organization

- **WHEN** Beta exists from 1 January, and a transaction ends it from 1 June
- **THEN** a view effective at 31 May finds Beta, and a view effective at 1 June does not

### Requirement: Corrections

A later transaction SHALL be able to change any effective period, in the past or in the future, with the same writes. A view at the basis of an earlier transaction SHALL show what was known then.

#### Scenario: Correcting a balance

- **WHEN** A-1's balance was patched to 1,500 from 15 March on 16 March, and a transaction recorded on 20 April corrects it to 1,200 from 15 March (RD-5)
- **THEN** a view effective at 31 March, known at 5 April, shows 1,500
- **AND** a view effective at 31 March, known now, shows 1,200

### Requirement: Uniqueness over effective time

THE SYSTEM SHALL reject a transaction that would give two entities the same value for a unique attribute at the same point in effective time. The check SHALL cover every entity whose type has the attribute, including subtypes.

#### Scenario: Duplicate LEI

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` from 1 January, and a transaction creates Gamma with the same LEI from 1 March (RD-9)
- **THEN** the transaction is rejected with a problem about the attribute `lei` of Gamma

#### Scenario: Reusing a value after it ends

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` until 1 March, and Gamma is created with the same LEI from 1 March
- **THEN** the transaction is committed

### Requirement: Conflict checks

A write MAY ask for a conflict check against the basis at which its caller read the entity: per entity, the write SHALL be rejected if any transaction after that basis changed the entity; per attribute, only if one changed an attribute that the write sets. Without a check, the last commit wins.

#### Scenario: Writers that change different attributes

- **WHEN** two callers read A-1 at the same basis, the first patches its balance, and the second then patches its currency with a per-attribute check
- **THEN** both transactions are committed

#### Scenario: Writers that change the same attribute

- **WHEN** two callers read A-1 at the same basis, the first patches its balance, and the second then patches its balance with a per-attribute check
- **THEN** the second transaction is rejected with a problem about the attribute `balance`
