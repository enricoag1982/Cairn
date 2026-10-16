## MODIFIED Requirements

### Requirement: Reading data

`cairn_read_entity`, `cairn_read_relationship`, `cairn_list_entities`, `cairn_navigate` and `cairn_traverse` SHALL read through a view whose coordinates the call gives: an effective time, and optionally a basis or a transaction time to read as of. `cairn_timeline` SHALL read the whole history in effective time, as of the latest basis or of the basis or transaction time the call gives. Every result SHALL report the coordinates it was read at. A read by an ID that the store does not know SHALL return a problem; an entity or relationship that is not effective at the given time SHALL read as nothing. `cairn_traverse` SHALL follow every relationship of the given name, up to the given maximum depth, or to the end when the call gives none. `cairn_read_entity`, `cairn_navigate` and `cairn_traverse` SHALL take, in place of the ID of the entity they read or start from, a unique key in the form of payloads (`key`), found at the call's effective time; a call SHALL give exactly one of the two, a key that is invalid SHALL return a problem of the code for invalid keys at `@arguments.key`, and a key that names no entity then SHALL read as nothing.

#### Scenario: Who owns Beta, as an agent

- **WHEN** the transactions of RD-2 are recorded, and an agent calls `cairn_navigate` from Beta by `ownedBy` effective at 1 March
- **THEN** the result is ACME, through an Owns relationship with the share 55, and the result reports its effective time and basis

#### Scenario: Reading an account by its number, as an agent

- **WHEN** the transactions of RD-5 are recorded, and an agent calls `cairn_read_entity` with the key `{ entityType: Account, key: { number: A-1 } }` effective at 31 March
- **THEN** the result is A-1 with the balance 1,200, and it reports its effective time and basis

#### Scenario: Traversing to the end, as an agent

- **WHEN** ACME owns 55% of Beta and Gamma owns 70% of ACME, and an agent calls `cairn_traverse` from Beta by `ownedBy` without a maximum depth, effective 1 June
- **THEN** the result holds ACME at depth 1 and Gamma at depth 2
