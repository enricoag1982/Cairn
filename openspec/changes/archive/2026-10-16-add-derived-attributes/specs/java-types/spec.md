## MODIFIED Requirements

### Requirement: Building writes

Every entity type that is not abstract SHALL have a builder for creating an entity, and every entity type builders for patching and replacing one; every relationship type SHALL have builders for relating two entities, patching and replacing. A builder SHALL have a setter for each attribute of the type that is not derived, a patch builder SHALL also clear optional attributes that are not derived, and every builder SHALL produce the write of the generic API for an effective period. A builder for relating SHALL also take the generated types of the relationship type's source and target. Builders SHALL NOT check values: the transaction checks them at commit.

#### Scenario: Creating Alice with a builder

- **WHEN** a transaction creates a Person with the builder, with the name Alice, from 1 January, and is committed
- **THEN** the transaction is committed, and Alice's state read as Person has the name Alice

#### Scenario: A builder does not check values

- **WHEN** a transaction creates a Person with the builder, without a name, and is committed
- **THEN** the commit is rejected with the problem that a required value is missing

#### Scenario: Relating with a builder

- **WHEN** ACME and Beta exist, and a transaction relates ACME to Beta with the builder of Owns, from the generated types of both, with the share 30, from 1 March
- **THEN** the transaction is committed, and the relationship's state read as Owns has the share 30 and ACME as its source

#### Scenario: Clearing an optional value with a builder

- **WHEN** ACME has an LEI, and a transaction patches ACME with the builder of Organization, clearing its LEI from 1 March
- **THEN** ACME's state from 1 March, read as Organization, has no LEI
