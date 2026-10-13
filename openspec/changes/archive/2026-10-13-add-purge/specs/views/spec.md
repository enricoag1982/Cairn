## MODIFIED Requirements

### Requirement: Views are bound to coordinates

THE SYSTEM SHALL read data only through views. A view SHALL be created with an effective time, and with a basis or a transaction time to read as of; without one, it SHALL read as of the latest basis when it is created. A view's coordinates SHALL be fixed when it is created: every read through one view SHALL return the same answer, whatever is committed later, except that after a purge no view returns the purged data (capability `purge`). A view SHALL report its effective time and its basis; a basis after the latest SHALL read as the latest. There is no implicit "now": the caller always gives the effective time (ADR-0013).

#### Scenario: Repeatable reads

- **WHEN** a view is created, and a transaction then changes ACME's name
- **THEN** reading ACME through the view still shows the earlier name, and a view created afterwards shows the new one

#### Scenario: Reading as of a transaction time

- **WHEN** a view is created effective at 31 March and known at 5 April, after the transactions of RD-5
- **THEN** its basis is that of the latest transaction recorded at or before 5 April

