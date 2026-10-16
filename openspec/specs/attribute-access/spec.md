# attribute-access Specification

## Purpose
TBD - created by archiving change add-attribute-access-control. Update Purpose after archive.

## Requirements

### Requirement: Classified attributes

An attribute of an entity type, relationship type or trait MAY declare one classification: a name of at most 128 ASCII lowercase letters, digits and hyphens that starts with a letter, such as `pay`. The schema SHALL report each attribute's classification. A derived attribute that reads classified attributes (see the `derived-attributes` capability) MUST have the classification that they have. A classification that is not such a name, or a derived attribute that reads attributes of another classification than its own or of two classifications, SHALL be a problem of the code `INVALID_CLASSIFICATION` at the attribute.

#### Scenario: Classifying a balance

- **WHEN** Account's `balance` declares the classification `finance`, and the definition is validated
- **THEN** validation succeeds, and the schema reports `finance` as the classification of `balance`

#### Scenario: A derived attribute that would reveal a classified one

- **WHEN** Account's `balance` is classified `finance`, and Account declares the test-only derived attribute `balanceInCents` without a classification
- **THEN** validation fails with a problem of the code `INVALID_CLASSIFICATION` located at `Account.balanceInCents`

#### Scenario: A malformed classification

- **WHEN** Account's `balance` declares the classification `Finance Data`
- **THEN** validation fails with a problem of the code `INVALID_CLASSIFICATION` located at `Account.balance`

### Requirement: Views restricted to a clearance

A clearance SHALL be a set of classifications. A view MAY be restricted to a clearance; a view that is not restricted SHALL read every value. A restricted view SHALL withhold the value of every attribute whose classification its clearance does not cover from every entity and relationship state it returns, including timelines, navigation, traversals, the states that a traversal's condition sees, and query rows, and each state SHALL list the attributes it withheld. A restricted view SHALL withhold the value of an attribute unless its clearance covers the attribute's classification both in the view's schema and in the store's latest schema, so that a view at an older basis withholds what has been classified since; an attribute that has a classification in neither SHALL never be withheld. A view restricted from a restricted view SHALL be restricted to the classifications that both clearances cover. The views of rules SHALL never be restricted.

#### Scenario: A balance withheld

- **WHEN** Account's `balance` is classified `finance`, the transactions of RD-1 and RD-5 are recorded, and A-1 is read through a view restricted to no classification, effective 1 April
- **THEN** A-1's state has its number and currency but no balance, and lists `balance` as withheld

#### Scenario: A clearance that covers the balance

- **WHEN** A-1 is read as in the previous scenario through a view restricted to the classification `finance`
- **THEN** A-1's state has its balance, and withholds nothing

#### Scenario: A balance classified later

- **WHEN** the transactions of RD-1 and RD-5 are recorded while `balance` has no classification, a later version of the reference model classifies Account's `balance` as `finance`, and A-1 is read through a view at the basis of RD-5's first transaction, restricted to no classification
- **THEN** A-1's state lists `balance` as withheld

#### Scenario: Restricting a restricted view

- **WHEN** a view restricted to `finance` is restricted again to `identity`, and A-1 is read through it
- **THEN** A-1's state lists `balance` as withheld

#### Scenario: Navigating to a withheld value

- **WHEN** Beta's `holds` is navigated through a view restricted to no classification, effective 1 April
- **THEN** the result is A-1, whose state lists `balance` as withheld

### Requirement: Queries and keys over withheld attributes

WHEN a query run through a restricted view names an attribute that the view withholds, in a filter, an order, a group, an aggregate, a projection or a traversal condition, THE SYSTEM SHALL reject the query with a problem of the code `ACCESS_DENIED` at that place in the query, and SHALL read nothing. Finding an entity through a restricted view by a unique key of an attribute that the view withholds SHALL fail as finding by a key that the schema does not allow does.

#### Scenario: Filtering on a withheld balance

- **WHEN** Account's `balance` is classified `finance`, and a query for Accounts with a balance above 1,000 runs through a view restricted to no classification
- **THEN** the query is rejected with a problem of the code `ACCESS_DENIED` at its filter

#### Scenario: Summing withheld balances

- **WHEN** a query sums the balances of Accounts through a view restricted to no classification
- **THEN** the query is rejected with a problem of the code `ACCESS_DENIED` at its aggregate

#### Scenario: A key of a withheld attribute

- **WHEN** Organization's `lei` is classified `identity`, the transactions of RD-1 and RD-9 are recorded, and a view restricted to no classification finds the Organization with the key `Organization(lei: 5493001KJTIIGC8Y1R12)`
- **THEN** the call fails

### Requirement: Commits with a clearance

A transaction MAY carry a clearance. WHEN a write of a transaction with a clearance sets or clears an attribute that the clearance does not cover, THE SYSTEM SHALL reject the transaction with a problem of the code `ACCESS_DENIED` at that attribute of the write, in commits and dry runs alike. A write of such a transaction that names an entity by a unique key of an attribute that the clearance does not cover SHALL be rejected with `ACCESS_DENIED` at the reference, without resolving the key, so that the outcome reveals nothing about the value. Values that a write does not touch SHALL carry forward whatever their classification. Rules, checks and derivations SHALL see every value, whatever the transaction's clearance. WHEN a transaction with a clearance is rejected with `ACCESS_DENIED`, its result SHALL hold only those problems: THE SYSTEM SHALL NOT validate, derive, check or run rules for it. A problem that a transaction with a clearance gets about an attribute that the clearance does not cover, or from a check or a derivation that reads such an attribute, SHALL name its code and place but SHALL carry no value of a withheld attribute, no found value, no message of the evaluation and no ID of another entity; a check's own message SHALL be kept, a rule's message SHALL NOT. A rule that fails with an error SHALL be reported to a transaction with a clearance without the error's message. A transaction with a clearance whose writes it may not make SHALL be refused before its idempotency key is compared (see the `transactions` capability). A transaction without a clearance SHALL write as before.

#### Scenario: Writing a withheld balance

- **WHEN** Account's `balance` is classified `finance`, and a transaction with no classification in its clearance patches A-1's balance to 1,500 from 1 May
- **THEN** the transaction is rejected with a problem of the code `ACCESS_DENIED` at the write's `balance`

#### Scenario: A patch that leaves a withheld balance

- **WHEN** a transaction with no classification in its clearance patches A-1's currency to `CHF` from 1 May
- **THEN** the transaction is committed, and A-1's balance on 1 May is still 1,200 as read through a view that is not restricted

#### Scenario: Naming an entity by a withheld key

- **WHEN** Organization's `lei` is classified `identity`, the transactions of RD-1 and RD-9 are recorded, and a transaction with no classification in its clearance patches the Organization with the key `Organization(lei: 5493001KJTIIGC8Y1R12)`
- **THEN** the transaction is rejected with a problem of the code `ACCESS_DENIED` at the write's reference, and so is the same write with a key whose value no entity has

#### Scenario: A refused unique value reveals nothing

- **WHEN** Organization's `lei` is classified `identity`, the transactions of RD-1 and RD-9 are recorded, and a dry run of a transaction with no classification in its clearance creates Gamma, an organization, with the LEI `5493001KJTIIGC8Y1R12`
- **THEN** the result holds only the problem `ACCESS_DENIED` at Gamma's `lei`, as it does for an LEI that no organization has

#### Scenario: A check that fails over a withheld value

- **WHEN** Organization's `lei` is classified `identity`, Organization declares the test-only check `lei-numeric` with the expression `self.name != 'Probe' || int(self.lei) > 0`, the transactions of RD-1 and RD-9 are recorded, and a transaction with no classification in its clearance renames ACME to `Probe`
- **THEN** the transaction is rejected with a problem of the code `CHECK_FAILED` that names `lei-numeric`, and no part of the problem contains the LEI

#### Scenario: A rule over a withheld value

- **WHEN** Account's `balance` is classified `finance`, A-1's balance is below 1,000, and a transaction with no classification in its clearance relates ACME to Alice with Employs, which the rule `account-a1-funded` checks
- **THEN** the transaction is rejected by `account-a1-funded/underfunded`, which read A-1's balance

### Requirement: Agents read and write through a clearance

The agent server SHALL take a clearance from the person who starts it, with no classification by default. Every agent tool SHALL read through views restricted to that clearance and SHALL submit every commit and dry run with it, and a payload SHALL NOT widen it. JSON results of states SHALL list the attributes that they withheld. The tool that describes the store SHALL report the clearance and the classification of each attribute. A query or a key that a restricted view refuses SHALL be a problem of the code `ACCESS_DENIED` in the tool's result. The status of an approval request SHALL be given only as the `agent-tools` capability says. A schema plan or registration by an agent SHALL be rejected with a problem of the code `ACCESS_DENIED` at the element concerned when it adds, changes or removes the classification of an attribute, where the classification before or after is one that the clearance does not cover, or when it adds a check or a derived attribute to any type, abstract or not, or changes the expression of one, that reads an attribute whose classification the clearance does not cover; nothing SHALL change.

#### Scenario: An agent without a clearance

- **WHEN** Account's `balance` is classified `finance`, the server starts without a clearance, the transactions of RD-1 and RD-5 are recorded, and an agent reads A-1
- **THEN** the result has A-1's number and currency, no balance, and lists `balance` as withheld

#### Scenario: An agent's query over a withheld attribute

- **WHEN** the agent of the previous scenario runs a query for Accounts with a balance above 1,000
- **THEN** the result is a problem of the code `ACCESS_DENIED`

#### Scenario: An agent with a clearance

- **WHEN** the server starts with the clearance `finance`, and an agent reads A-1
- **THEN** the result has A-1's balance, and withholds nothing

#### Scenario: An agent that would declassify the balance

- **WHEN** Account's `balance` is classified `finance`, the server starts without a clearance, and an agent registers a later version of the reference model in which `balance` has no classification
- **THEN** the registration is rejected with a problem of the code `ACCESS_DENIED` located at `Account.balance`, and `balance` is still classified `finance`

#### Scenario: An agent's check over a withheld balance

- **WHEN** the agent of the previous scenario registers a later version in which Account declares the check `balance-above-1000` with the expression `self.balance > 1000.0`
- **THEN** the registration is rejected with a problem of the code `ACCESS_DENIED` located at `Account.balance-above-1000`

#### Scenario: An agent's check on a new abstract type

- **WHEN** Account's `balance` is classified `finance`, the server starts without a clearance, and an agent registers a later version that adds an abstract subtype of Account declaring the check `probe` with the expression `self.balance > 1000.0`
- **THEN** the registration is rejected with a problem of the code `ACCESS_DENIED` located at the check
