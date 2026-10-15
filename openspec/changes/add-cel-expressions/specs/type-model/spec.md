## MODIFIED Requirements

### Requirement: Validation reports every problem

WHEN a schema definition is invalid, THE SYSTEM SHALL report every problem it finds, not only the first. Each problem SHALL have a stable code, the path of the element it concerns (a type name, or a type name and an attribute, inverse name, rule name or check name joined by a dot) and a readable message. An invalid definition MUST NOT change the stored schema.
Where it applies, a problem SHALL also state what was expected, what was found, and a hint for fixing it. A problem about a name that refers to nothing SHALL give as its hint the closest name that exists, when one differs from it by at most two letters, compared without regard to case.

#### Scenario: Several problems at once

- **WHEN** a definition declares two types named Party and a range on the boolean attribute `active` of Account
- **THEN** validation reports two problems, each with its code, one with the path `Party` and one with the path `Account.active`
- **AND** the stored schema is unchanged

#### Scenario: Hint for a misspelled reference

- **WHEN** the supertype of Organization is written `Prty`
- **THEN** validation fails with a problem located at `Organization` whose hint names Party

#### Scenario: Expected and found values

- **WHEN** an entity type is named `1Party`
- **THEN** validation fails with a problem that states the form a name must have, and that it found `1Party`
