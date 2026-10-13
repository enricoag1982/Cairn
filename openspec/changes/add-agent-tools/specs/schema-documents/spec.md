## MODIFIED Requirements

### Requirement: Problems are located in the document

Every problem about a document SHALL carry its location in the document: a JSON Pointer, a line and a column, both counted from 1. Its path SHALL be the path of the element it is in, `@origin` inside the origin, or `@document` outside every element. For a document that was read, THE SYSTEM SHALL locate the problems that validating, planning or registering its definition reports: a problem about a type, attribute, inverse name or rule SHALL be located at that element, a problem about the origin at the key `origin`, a problem about an element that the document does not have, such as an attribute that a new version removes, at the closest enclosing element that the document has, and a problem whose path names no element of the document at the document's root.

#### Scenario: Locating a registration problem

- **WHEN** a document whose entity type Organization names the supertype `Prty` is read, registered, and the registration's problems are located in the document
- **THEN** the problem about Organization is located at the JSON Pointer `/entityTypes/Organization`, with the line and column where Organization is defined

#### Scenario: Problem outside every element

- **WHEN** a document has the top-level key `entityType` instead of `entityTypes`
- **THEN** reading fails with a problem whose path is `@document`, located at the key, whose hint names `entityTypes`

#### Scenario: Locating an attribute problem

- **WHEN** a document declares a pattern on the decimal attribute `balance` of Account, and validation's problems are located in the document
- **THEN** the problem is located at the JSON Pointer `/entityTypes/Account/attributes/balance`

#### Scenario: Locating a removed attribute

- **WHEN** a document of a new version of an origin removes the attribute `street` of Address, is registered, and the registration's problems are located in the document
- **THEN** the breaking change at `Address.street` is located at the JSON Pointer `/entityTypes/Address`, with the line and column where Address is defined
