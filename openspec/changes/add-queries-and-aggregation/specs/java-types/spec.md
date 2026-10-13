## MODIFIED Requirements

### Requirement: Names in Java

Generated names SHALL be the names in the documents. A name that would not compile or would clash SHALL get the suffix `_`, repeated until it clashes with nothing: Java keywords, restricted identifiers and the literals `true`, `false` and `null`; for attributes also the methods of `Object`, the simple names of library types that generated code uses, and the names of members that generated code adds to types and builders; and rule base class names that equal type names. The typed reference of an attribute SHALL be a constant named after the attribute in upper snake case, such as `BIRTH_DATE` for `birthDate`, with the suffix `_`, repeated, where it would clash with `TYPE`, a generated type, or a constant that the type inherits. Generated code SHALL compile whatever the names of the types, including names of Java library types.

#### Scenario: An attribute named like a Java keyword

- **WHEN** types are generated for a document whose entity type Setting has the attribute `default`
- **THEN** Setting has the accessor `default_`, and the generated code compiles without warnings

#### Scenario: Types named like Java library types

- **WHEN** types are generated for a document with the entity types `Map`, `Optional` and `String`
- **THEN** the generated code compiles without warnings

## ADDED Requirements

### Requirement: Typed queries

Every generated entity type, trait and relationship type SHALL have a typed reference for each attribute it declares, which builds the query conditions, orders, group keys and aggregates of that attribute for a binding, taking values of the attribute's Java type. A row of a query result SHALL read a binding as a generated type with `from`, and with the view's schema as the nearest generated type that the binding's type is or extends, so that an entity of a subtype registered after generation reads as its nearest known supertype.

#### Scenario: A typed query

- **WHEN** the transactions of RD-5 are recorded, and a query for Accounts built with the typed reference of `balance` keeps the balances greater than 1200, and its rows are read as Account, effective 31 March as known now
- **THEN** it returns one Account, A-2, with the balance 2000

#### Scenario: A subtype registered after generation in a query

- **WHEN** a schema that adds the entity type Bank with the supertype Organization is registered after the types were generated, a Bank is created, and a query for Parties reads its rows as Party with the view's schema
- **THEN** the Bank reads as an Organization
