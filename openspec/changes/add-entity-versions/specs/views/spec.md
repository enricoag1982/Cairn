## ADDED Requirements

### Requirement: Views are bound to coordinates

THE SYSTEM SHALL read data only through views. A view SHALL be created with an effective time, and with a basis or a transaction time to read as of; without one, it SHALL read as of the latest basis when it is created. A view's coordinates SHALL be fixed when it is created: every read through one view SHALL return the same answer, whatever is committed later. A view SHALL report its effective time and its basis.

#### Scenario: Repeatable reads

- **WHEN** a view is created, and a transaction then changes ACME's name
- **THEN** reading ACME through the view still shows the earlier name, and a view created afterwards shows the new one

#### Scenario: Reading as of a transaction time

- **WHEN** a view is created effective at 31 March and known at 5 April, after the transactions of RD-5
- **THEN** its basis is that of the latest transaction recorded at or before 5 April

### Requirement: Reading an entity

A view SHALL return the state of an entity at its effective time, as known at its basis: the entity's ID, its type, the values of its attributes, the effective period of the version, and the transaction time and basis that recorded it. It SHALL return nothing for an entity that does not exist at that effective time, or that was not yet recorded at that basis.
Values SHALL be reported under the attribute names of the view's schema, and in the attribute's current value type.

#### Scenario: Not yet recorded

- **WHEN** ACME was created by a transaction recorded on 5 January, and it is read through a view known at 4 January
- **THEN** the view returns nothing

#### Scenario: Reading after a rename

- **WHEN** ACME's LEI was written before the attribute `lei` of Organization was renamed to `legalEntityId` with the alias `lei`
- **THEN** a view created after the rename reports the value under `legalEntityId`

### Requirement: Listing entities of a type

A view SHALL list the entities of an entity type that exist at its effective time, including the entities of the type's subtypes, ordered by ID.

#### Scenario: Polymorphic listing

- **WHEN** ACME, Beta and Alice exist, and the entities of Party and of Organization are listed through a view effective at 31 March (RD-10)
- **THEN** Party lists ACME, Beta and Alice, and Organization lists ACME and Beta

### Requirement: Timeline of an entity

A view SHALL return the timeline of an entity as known at its basis: every version across effective time, ordered by the start of its effective period, each with its period and values.

#### Scenario: Timeline with a future-dated change

- **WHEN** ACME exists from 1 January with the name "ACME", and its name is "ACME Holdings" from 1 January of the next year (RD-7)
- **THEN** ACME's timeline has two versions: "ACME" from 1 January until 1 January of the next year, and "ACME Holdings" from then on, until further notice
