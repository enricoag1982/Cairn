## MODIFIED Requirements

### Requirement: Rules in Java

A rule SHALL be a Java object that describes itself, with the name, version, description, type and problem codes of an available rule, and that checks one version of an entity or of a relationship: a rule for an entity type checks entity versions, and a rule for a relationship type checks relationship versions. A store SHALL be created with its rules, and their descriptions SHALL be among the rules available to the store's schema registry. THE SYSTEM SHALL also find rules provided through Java's service loader when the store is created to do so. Two rules with the same name and version MUST NOT be given to one store. A store MAY also declare rules whose code it does not have (*declared rules*): schemas may name them, but a transaction that writes an entity whose type, or one of whose supertypes, names one SHALL be rejected with a problem that names the rule. A rule that a type names but that does not check versions of that kind SHALL reject the first transaction that writes such an entity or relationship, with a problem that names the rule.

#### Scenario: Rules become available to the schema

- **WHEN** a store is created with the rule `lei-checksum` version 1 and declares `total-ownership-at-most-100` version 1, and the reference domain model, which names both, is registered
- **THEN** the registration is applied, and the store's schema registry lists `lei-checksum` among its available rules

#### Scenario: A declared rule without code

- **WHEN** a store declares the rule `name-not-blank` for Party without its code, Party names it, and a transaction creates Alice
- **THEN** the transaction is rejected with a problem that names the rule `name-not-blank`

## ADDED Requirements

### Requirement: Rules on relationships

A rule MAY apply to a relationship type. THE SYSTEM SHALL run it, as it runs rules on entities, for every version of a relationship of that type or of its subtypes whose values the transaction changes, and for the relationships that a transaction relates. A rule whose verdict depends on other relationships, such as a sum over the relationships of one entity, SHALL be named by the relationship type whose writes can break it, and SHALL use its rule context to check every instant of the version's period at which those relationships change.

#### Scenario: Owners holding more than 100%

- **WHEN** ACME owns 55% of Beta from 1 February, and a transaction recorded on 1 April creates Gamma and relates it to Beta with Owns, with the share 50, from 1 April (RD-13)
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`

#### Scenario: Shares that change later in the period

- **WHEN** ACME owns 40% of Beta from 1 February, Gamma owns 50% of Beta from 1 April, and a transaction patches ACME's share to 55 from 1 February
- **THEN** the transaction is rejected with a problem from the rule `total-ownership-at-most-100`, because from 1 April the owners would hold 105%
