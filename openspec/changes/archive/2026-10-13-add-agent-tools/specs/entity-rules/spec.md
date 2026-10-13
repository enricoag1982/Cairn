## MODIFIED Requirements

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
