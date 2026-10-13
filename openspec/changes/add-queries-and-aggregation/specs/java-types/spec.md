## ADDED Requirements

### Requirement: Typed queries

Every generated entity type, trait and relationship type SHALL have a typed reference for each attribute it declares, which builds the query conditions, orders, group keys and aggregates of that attribute for a binding, taking values of the attribute's Java type. A row of a query result SHALL read a binding as a generated type with `from`.

#### Scenario: A typed query

- **WHEN** the transactions of RD-5 are recorded, and a query for Accounts built with the typed reference of `balance` keeps the balances greater than 1200, and its rows are read as Account, effective 31 March as known now
- **THEN** it returns one Account, A-2, with the balance 2000
