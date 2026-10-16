## MODIFIED Requirements

### Requirement: Queries

THE SYSTEM SHALL run a query against a view and return rows that reflect exactly the view's coordinates. A query SHALL start from an entity type, binding each entity of the type that is effective at the view's effective time to a name; it SHALL include the entities of the type's subtypes unless it asks for the exact type. A query MAY start from given entities instead: it SHALL then bind only the entities with the given IDs that are of the type, or of its subtypes unless it asks for the exact type, and effective at the view's effective time, each once, and leave out the others without a problem, so that an empty list of IDs gives no rows; run in a rule's view, it SHALL read those entities and not every entity of the type. Its steps SHALL then apply in order, each to the rows that the steps before it produced. A query SHALL be an immutable value that can be built in Java, written as JSON, and run again at other coordinates.

#### Scenario: Parties and organizations

- **WHEN** the transactions of RD-1 are recorded, and queries for Party and for Organization are run effective 31 March
- **THEN** the query for Party returns ACME, Beta and Alice, and the query for Organization returns ACME and Beta

#### Scenario: The exact type

- **WHEN** the transactions of RD-1 are recorded, and a query for exactly the type Party is run
- **THEN** it returns no rows, because Party is abstract

#### Scenario: A query that starts from given entities

- **WHEN** the transactions of RD-1 and RD-5 are recorded, and a query for Party starts from the IDs of ACME and Alice and of the account A-1, effective 31 March
- **THEN** it returns ACME and Alice, and leaves out A-1, which is not a Party

#### Scenario: A rule that queries from one entity

- **WHEN** the test-only rule `account-a1-queried` on Employs reads A-1's balance with a query for Account that starts from A-1's ID, the transactions of RD-1, RD-3 and RD-5 are recorded, and a transaction changes A-2's balance
- **THEN** the rule does not run again for Beta's employment of Alice, because it read A-1 and not every account


### Requirement: Traversals

A traversal step SHALL, for each row, bind each entity reached from a bound entity by a relationship name or inverse name, at depths from a minimum to a maximum, or to the end when the step gives no maximum, where depth 0 is the bound entity itself, with the relationship that reached it and its depth. A traversal MAY follow only the relationships that meet a condition on the relationship. Each reached entity SHALL be bound once per row, at the smallest depth at which it is reached. A row whose binding the traversal starts from is empty, or from which nothing is reached at those depths, SHALL be dropped. The entity type of the new binding SHALL be the closest common supertype of the types it can hold: the type at the other end of the relationship, and with a minimum depth of 0 the type of the binding it starts from; without one, only the binding's ID, type and links can be used.

#### Scenario: Beta and its owners

- **WHEN** the transactions of RD-2 are recorded, and a query for the Organization named Beta traverses `ownedBy` from depth 0 to depth 5, effective 1 March
- **THEN** it returns Beta at depth 0 and ACME at depth 1

#### Scenario: Following only large ownerships

- **WHEN** the transactions of RD-2 are recorded, and a query for the Organization named Beta traverses `ownedBy` from depth 1 along the ownerships whose share is above 60, effective 1 March
- **THEN** it returns no rows, because ACME's share is 55

#### Scenario: A traversal that starts at depth 0 from a person

- **WHEN** the transactions of RD-1 and RD-5 are recorded, and a query for Accounts follows `HeldBy` to the holder and traverses `ownedBy` from the holder from depth 0, and filters the reached parties on `name`
- **THEN** the query is valid, because the reached binding is a Party, and it returns the rows without failing

#### Scenario: A traversal to the end in a query

- **WHEN** ACME owns 55% of Beta and Gamma owns 70% of ACME, and a query for the Organization named Beta traverses `ownedBy` from depth 1 without a maximum depth
- **THEN** it returns ACME at depth 1 and Gamma at depth 2


### Requirement: Results

A query SHALL return the columns it names: bindings, attributes of bindings, depths of traversals, group keys and aggregates; without names, a query without grouping SHALL return its bindings, and a grouped query its keys and aggregates. A binding SHALL be returned as the state of its entity or relationship at the view's coordinates. Rows SHALL be ordered by the order the query gives, then by the IDs of the bindings in the order they were bound, or, for grouped rows, by their keys, so that the same query on the same data returns the same rows in the same order whatever stores them; missing values SHALL come first in ascending order. A limit SHALL return the first rows only. The result SHALL report the coordinates it was read at.
A row SHALL read a value as its Java type (`String`, `boolean`, `int`, `long`, `BigDecimal`, `LocalDate` or `Instant`), any numeric value as a `BigDecimal`, and a binding as a generated type through a function given to it; reading a cell as another kind SHALL fail with an exception that names the column and both kinds, and an empty cell SHALL read as nothing.

#### Scenario: The account with the largest balance

- **WHEN** the transactions of RD-5 are recorded, and a query for Accounts ordered by balance, largest first, with the limit 1, is run effective 31 March
- **THEN** it returns A-2

#### Scenario: Reading a total without casts

- **WHEN** the transactions of RD-5 are recorded, and a query counts the Accounts and totals their balances, effective 31 March as known now, and the row is read with the typed accessors
- **THEN** the count reads as the long 2, the total as the decimal 3200, and reading the count as a string fails with an exception that names its column


### Requirement: Queries as JSON

THE SYSTEM SHALL read a query written as JSON, or as YAML 1.2, in a form that mirrors the query's data one to one, with a published JSON Schema. Problems of reading SHALL be located in the text, and problems of the query in the view's schema SHALL be located at the part of the text they concern. Query results SHALL be written as JSON, with their columns, rows and coordinates.

#### Scenario: The total balance as JSON

- **WHEN** the query of the scenario "Total balance by ultimate parent" is written as JSON, read, and run effective 31 March as known now
- **THEN** it returns the same rows as the query built in Java: ACME with 3200

#### Scenario: A JSON query with a misspelled key

- **WHEN** a JSON query has a step with the key `folow`
- **THEN** reading it returns a problem located at that key, whose hint names `follow`

#### Scenario: Starting from given entities as JSON

- **WHEN** a query written as JSON starts from Party with the `ids` of ACME and Alice, and a traversal without `maxDepth`, and is read and run
- **THEN** it returns the same rows as the same query built in Java

