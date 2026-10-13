## ADDED Requirements

### Requirement: Rules on relationships

A rule MAY apply to a relationship type. THE SYSTEM SHALL run it, as it runs rules on entities, for every version of a relationship of that type or of its subtypes that a transaction would produce. Through its rule context, a rule SHALL be able to read the state the transaction would produce at any effective time, so that it can check every point of the version's period.

#### Scenario: Owners holding more than 100%

- **WHEN** ACME owns 55% of Beta from 1 February, and a transaction recorded on 1 April creates Gamma and relates it to Beta with Owns, with the share 50, from 1 April (RD-13)
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`

#### Scenario: Shares that change later in the period

- **WHEN** ACME owns 40% of Beta from 1 February, Gamma owns 50% of Beta from 1 April, and a transaction patches ACME's share to 55 from 1 February
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`, because from 1 April the owners would hold 105%
