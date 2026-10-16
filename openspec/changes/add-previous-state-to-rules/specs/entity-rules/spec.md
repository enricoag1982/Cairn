## MODIFIED Requirements

### Requirement: Rules run at commit

WHEN a transaction would change the values of an entity or relationship whose type, or one of whose supertypes, names a rule, THE SYSTEM SHALL run the rule for every version whose values the transaction changes, after the declarative constraints and the integrity checks have passed for it. A write that only ends an entity or relationship changes no values, so it runs none of its rules, though it can run rules again for what read it. A rule's verdict MUST therefore hold at each instant of the version's period and MUST NOT depend on where that period starts or ends. A rule that needs the current time SHALL use the transaction time of the version it checks, never a clock. The rule SHALL see the version's values and effective period, and SHALL be able to read other entities and relationships, and navigate between them, through a rule context, as they would be after the transaction, at the start of the version's effective period or at any other effective time. The rule SHALL also be able to read the store as it was committed before the transaction, at the same effective times, through views whose reads are recorded as other reads are. A rule MUST NOT be able to write: a write attempted while rules run SHALL fail, and the rule SHALL be reported as failed.
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

#### Scenario: A rule that reads what was there before

- **WHEN** the test-only rule `lei-kept` is named on Organization, the transactions of RD-9 give ACME its LEI from 1 January, and a transaction removes ACME's LEI from 1 March
- **THEN** the transaction is rejected with a problem whose code is `lei-kept/lei-removed`, about ACME

#### Scenario: Nothing before a creation

- **WHEN** the test-only rule `lei-kept` is named on Organization, and a transaction creates Gamma without an LEI
- **THEN** the transaction commits, because the store held no Gamma before it

