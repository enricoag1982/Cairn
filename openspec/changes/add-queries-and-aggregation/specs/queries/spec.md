# Spec Delta

## Purpose

Defines queries: data that a view runs to filter entities, follow and traverse relationships, group and aggregate, and return rows; what each step means, how results are ordered, the problems of invalid queries, and the JSON form of queries and results.

## ADDED Requirements

### Requirement: Queries

THE SYSTEM SHALL run a query against a view and return rows that reflect exactly the view's coordinates. A query SHALL start from an entity type, binding each entity of the type that is effective at the view's effective time to a name; it SHALL include the entities of the type's subtypes unless it asks for the exact type. Its steps SHALL then apply in order, each to the rows that the steps before it produced. A query SHALL be an immutable value that can be built in Java, written as JSON, and run again at other coordinates.

#### Scenario: Parties and organizations

- **WHEN** the transactions of RD-1 are recorded, and queries for Party and for Organization are run effective 31 March
- **THEN** the query for Party returns ACME, Beta and Alice, and the query for Organization returns ACME and Beta

#### Scenario: The exact type

- **WHEN** the transactions of RD-1 are recorded, and a query for exactly the type Party is run
- **THEN** it returns no rows, because Party is abstract

### Requirement: Filters

A filter step SHALL keep the rows for which its condition holds. A condition SHALL compare an attribute of a binding, its ID or its type with a value or with an attribute of a binding; test whether such an operand is one of a list of values; test whether an attribute has a value; test whether a bound entity is of an entity type or one of its subtypes; test whether a bound entity has a link by a relationship name or inverse name; or combine conditions with and, or and not. Numbers SHALL compare by value whatever their value types; dates, instants and strings SHALL compare in their order, strings by code point; booleans SHALL compare for equality only. A comparison with an attribute that has no value, or of a binding that is empty, SHALL be false, so that only the test for a value tells missing values apart.

#### Scenario: Accounts above a balance

- **WHEN** the transactions of RD-5 are recorded, and a query for the Accounts whose balance is greater than 1200 is run effective 31 March
- **THEN** it returns A-1 and A-2 as known on 5 April, and only A-2 as known now

#### Scenario: Persons without a birth date

- **WHEN** the transactions of RD-1 are recorded, and a query for the Persons that have no value for `birthDate` is run
- **THEN** it returns Alice

### Requirement: Following relationships

A follow step SHALL, for each row, bind the entity at the other end of each relationship that leads from a bound entity by a relationship name or inverse name and is effective at the view's effective time, as navigation does, giving one row per relationship. It MAY also bind the relationship. A follow step SHALL drop a row whose binding it follows from is empty, and a row that has no such relationship, unless it is optional: an optional follow step SHALL keep such a row, with its new bindings empty.

#### Scenario: Who owns Beta, with the share

- **WHEN** the transactions of RD-2 are recorded, and a query for the Organization named Beta follows `ownedBy`, binding the owner and the relationship, effective 1 March
- **THEN** it returns one row, with ACME as the owner and a relationship whose share is 55

#### Scenario: Organizations with their employees, if any

- **WHEN** the transactions of RD-1 and RD-3 are recorded, and a query for Organizations optionally follows `Employs`, binding the employee, effective 1 April
- **THEN** it returns ACME without an employee, and Beta with Alice

### Requirement: Traversals

A traversal step SHALL, for each row, bind each entity reached from a bound entity by a relationship name or inverse name, at depths from a minimum to a maximum, where depth 0 is the bound entity itself, with the relationship that reached it and its depth. A traversal MAY follow only the relationships that meet a condition on the relationship. Each reached entity SHALL be bound once per row, at the smallest depth at which it is reached. A row whose binding the traversal starts from is empty, or from which nothing is reached at those depths, SHALL be dropped. The entity type of the new binding SHALL be the closest common supertype of the types it can hold: the type at the other end of the relationship, and with a minimum depth of 0 the type of the binding it starts from; without one, only the binding's ID, type and links can be used.

#### Scenario: Beta and its owners

- **WHEN** the transactions of RD-2 are recorded, and a query for the Organization named Beta traverses `ownedBy` from depth 0 to depth 5, effective 1 March
- **THEN** it returns Beta at depth 0 and ACME at depth 1

#### Scenario: Following only large ownerships

- **WHEN** the transactions of RD-2 are recorded, and a query for the Organization named Beta traverses `ownedBy` from depth 1 along the ownerships whose share is above 60, effective 1 March
- **THEN** it returns no rows, because ACME's share is 55

#### Scenario: A traversal that starts at depth 0 from a person

- **WHEN** the transactions of RD-1 and RD-5 are recorded, and a query for Accounts follows `HeldBy` to the holder and traverses `ownedBy` from the holder from depth 0, and filters the reached parties on `name`
- **THEN** the query is valid, because the reached binding is a Party, and it returns the rows without failing

### Requirement: Grouping and aggregation

A query MAY group its rows by keys, which are bindings or attributes of bindings, and compute aggregates for each group: the count of rows, and the count, sum, minimum, maximum and average of the values of an attribute, where missing values are left out. Without keys, all rows SHALL form one group, which SHALL exist even when there are no rows: its counts and sums are 0, and its minimum, maximum and average have no value. With keys, a group SHALL exist only for keys that rows have. Sums SHALL be exact, and averages SHALL be rounded to 34 significant digits.

#### Scenario: Total balance by ultimate parent

- **WHEN** the transactions of RD-1, RD-2 and RD-5 are recorded, and a query for Accounts follows `HeldBy` to the holder, traverses `ownedBy` from the holder from depth 0 along the ownerships whose share is above 50, keeps the reached parties that have no such ownership, groups by them and sums the balances, effective 31 March
- **THEN** as known on 5 April, it returns ACME with 3500, and as known now, ACME with 3200

#### Scenario: Counting no rows

- **WHEN** the transactions of RD-1 are recorded, and a query for the Persons named Bob counts its rows
- **THEN** it returns one row, with the count 0

### Requirement: Results

A query SHALL return the columns it names: bindings, attributes of bindings, depths of traversals, group keys and aggregates; without names, a query without grouping SHALL return its bindings, and a grouped query its keys and aggregates. A binding SHALL be returned as the state of its entity or relationship at the view's coordinates. Rows SHALL be ordered by the order the query gives, then by the IDs of the bindings in the order they were bound, or, for grouped rows, by their keys, so that the same query on the same data returns the same rows in the same order whatever stores them; missing values SHALL come first in ascending order. A limit SHALL return the first rows only. The result SHALL report the coordinates it was read at.

#### Scenario: The account with the largest balance

- **WHEN** the transactions of RD-5 are recorded, and a query for Accounts ordered by balance, largest first, with the limit 1, is run effective 31 March
- **THEN** it returns A-2

### Requirement: Invalid queries

WHEN a query names a type, binding, attribute or relationship name that the view's schema or the query does not have, binds a name twice, compares values that cannot be compared, or aggregates values that cannot be aggregated, THE SYSTEM SHALL return problems, each with the path of the step and part of the query, and, where a name is close to a valid one, a hint naming it. It SHALL read nothing then, and SHALL NOT throw.

#### Scenario: A misspelled attribute

- **WHEN** a query for Accounts filters on the attribute `balanse`
- **THEN** the query returns a problem at that filter whose hint names `balance`, and no rows

#### Scenario: Summing names

- **WHEN** a query for Parties sums the attribute `name`
- **THEN** the query returns a problem that says a sum needs numbers

### Requirement: Queries as JSON

THE SYSTEM SHALL read a query written as JSON, or as YAML 1.2, in a form that mirrors the query's data one to one, with a published JSON Schema. Problems of reading SHALL be located in the text, and problems of the query in the view's schema SHALL be located at the part of the text they concern. Query results SHALL be written as JSON, with their columns, rows and coordinates.

#### Scenario: The total balance as JSON

- **WHEN** the query of the scenario "Total balance by ultimate parent" is written as JSON, read, and run effective 31 March as known now
- **THEN** it returns the same rows as the query built in Java: ACME with 3200

#### Scenario: A JSON query with a misspelled key

- **WHEN** a JSON query has a step with the key `folow`
- **THEN** reading it returns a problem located at that key, whose hint names `follow`
