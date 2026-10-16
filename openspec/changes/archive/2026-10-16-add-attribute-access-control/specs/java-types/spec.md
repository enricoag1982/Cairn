## MODIFIED Requirements

### Requirement: Reading states as generated types

Every entity and relationship type SHALL have a snapshot record: an immutable record that holds the generic state it was read from and every attribute of the type. `from`, on the interface of a type, SHALL read a state as that type: as the snapshot record of the state's own type if it is that type or a generated subtype, and as the snapshot record of the asked type if the state's type is one that the generated code does not know. Reading a state of a generated type that is neither the asked type nor one of its subtypes, or a state that lacks a required value, SHALL fail as a programming error, unless the state withheld that value (see the `attribute-access` capability): such a state SHALL be read, and the accessor of an attribute that the state withheld SHALL fail as a programming error that names the attribute.

#### Scenario: Reading Alice as a Person

- **WHEN** the transactions of RD-1 are recorded, and Alice's state is read as Person
- **THEN** the result has the name Alice, no birth date, and the ID of Alice's state

#### Scenario: Reading an organization as a party

- **WHEN** the transactions of RD-1 are recorded, and ACME's state is read as Party
- **THEN** the result is an Organization with the name ACME

#### Scenario: A subtype registered after generation

- **WHEN** a schema that adds the entity type Bank with the supertype Organization is registered after the types were generated, a Bank is created, and its state is read as Organization
- **THEN** the result is an Organization whose state has the type Bank

#### Scenario: A state of another type

- **WHEN** the state of an Address is read as Person
- **THEN** reading fails with an exception

#### Scenario: Reading a withheld balance

- **WHEN** Account's `balance` is classified `finance` in the document that the types are generated from, the transactions of RD-1 and RD-5 are recorded, and A-1 is read as an Account through a view restricted to no classification
- **THEN** the Account gives A-1's number, and asking it for the balance fails with an error that names `balance`
