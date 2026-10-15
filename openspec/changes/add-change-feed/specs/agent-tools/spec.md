## ADDED Requirements

### Requirement: Reading changes

`cairn_list_changes` SHALL read the changes of the store after a basis that the call gives, 0 if it gives none, in basis order, at most as many as the call's limit, 100 if it gives none, and at most 1,000. It SHALL return the changes as JSON, in the form of the `json-payloads` capability, with `next`, the basis to give as `after` to continue, which is the basis of the last change returned or the given one if none, and the store's latest basis. An `after` below 0 or a limit outside 1 to 1,000 SHALL return a problem at `@arguments.after` or `@arguments.limit`. The tool only reads, so the approval policy SHALL NOT apply to it.

#### Scenario: An agent polls for changes

- **WHEN** the transactions of RD-1 are committed, an agent calls `cairn_list_changes` without a basis, the transactions of RD-3 are committed, and the agent calls it again with the `next` it received
- **THEN** the second result holds only RD-3's change, which lists the Employs relationship of Beta and Alice as created, and its `next` is RD-3's basis

#### Scenario: A limit out of range

- **WHEN** an agent calls `cairn_list_changes` with the limit 0
- **THEN** the result is a problem at `@arguments.limit`
