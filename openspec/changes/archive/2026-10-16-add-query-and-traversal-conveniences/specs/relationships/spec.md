## MODIFIED Requirements

### Requirement: Traversing relationships

A view SHALL traverse relationships recursively from an entity by a relationship type's name or inverse name, following only relationships that satisfy a condition, when one is given, up to a maximum depth, when one is given, and otherwise to the end. The traversal SHALL be breadth first. Each entity SHALL be visited at most once, through the first relationship that reaches it, and the start entity is never part of the result, so cycles end the traversal. A maximum depth of 0 SHALL give nothing, and a negative one SHALL fail. The result SHALL list each reached entity with its depth and the relationship it was reached through.

#### Scenario: Ultimate parent

- **WHEN** ACME owns 55% of Beta, and the traversal from Beta follows `ownedBy` while the share is above 50, with a maximum depth of 10
- **THEN** the traversal reaches ACME at depth 1, which is Beta's ultimate parent

#### Scenario: Ownership cycle

- **WHEN** ACME owns 60% of Beta and Beta owns 60% of ACME, and the traversal from Beta follows `ownedBy` while the share is above 50
- **THEN** the traversal reaches ACME at depth 1 and stops

#### Scenario: A traversal to the end

- **WHEN** ACME owns 55% of Beta and Gamma owns 70% of ACME, and the traversal from Beta follows `ownedBy` without a condition or a maximum depth
- **THEN** the traversal reaches ACME at depth 1 and Gamma at depth 2

