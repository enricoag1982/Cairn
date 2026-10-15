## ADDED Requirements

### Requirement: Reading an entity by a unique key

A view SHALL find the entity of an entity type, or of one of its subtypes, that has a value of a unique attribute of that type at the view's effective time, as known at its basis, and SHALL return it as it returns an entity read by its ID, or nothing if no entity has the value then. Finding by a type that the view's schema does not have, by an attribute that the type does not have or that is not unique, or by a value that does not fit the attribute's value type SHALL fail.

#### Scenario: Finding an account by its number

- **WHEN** the transactions of RD-5 are recorded, and the Account with the number `A-1` is found through a view effective at 31 March
- **THEN** the view returns A-1, with the balance 1,200

#### Scenario: Finding by a value that moved

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` until 1 March and Gamma has it from 1 March, and the Organization with that LEI is found through views effective at 15 February and at 15 March
- **THEN** the first view returns ACME and the second returns Gamma

#### Scenario: Finding by an attribute that is not unique

- **WHEN** the Account with the currency `EUR` is found through a view
- **THEN** finding fails, naming the attribute
