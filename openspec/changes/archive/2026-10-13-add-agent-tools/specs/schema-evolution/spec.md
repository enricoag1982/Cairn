## ADDED Requirements

### Requirement: Who and why of schema changes

A registration MAY name the principal that submits it, the party it acts for and a reason. THE SYSTEM SHALL record them with the schema transaction and SHALL list them in the schema's history. A registration that names no principal SHALL be recorded with the principal `@unknown`.

#### Scenario: An agent's schema change in the history

- **WHEN** the principal `agent:modeler` registers a version of `reference-model` that adds the optional attribute `email` to Person, with the reason "Contact data"
- **THEN** the schema's history lists that schema transaction with the principal `agent:modeler` and the reason "Contact data"
