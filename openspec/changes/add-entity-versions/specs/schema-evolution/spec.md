## ADDED Requirements

### Requirement: A store's schema registry

THE SYSTEM SHALL give every store its own schema registry. Its schema transactions SHALL take their transaction times and bases from the store's sequence, which data transactions share, so that a basis identifies one snapshot of both the schema and the data.

#### Scenario: Schema and data at one basis

- **WHEN** a store registers the reference domain model, commits RD-1, and then registers a version that adds the optional attribute `email` to Person
- **THEN** a view at the basis of RD-1 reads its entities with the schema that has no `email`
