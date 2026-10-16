## MODIFIED Requirements

### Requirement: Idempotency keys

A transaction MAY carry an idempotency key. WHEN a transaction with a key that a committed transaction already used is submitted with the same writes, principal, party acted for, reason and clearance (see the `attribute-access` capability), THE SYSTEM SHALL return the earlier receipt and record nothing. The key SHALL be checked before the writes are validated, after a transaction with a clearance is refused for writes that its clearance does not allow (see the `attribute-access` capability), so a retry gets the earlier receipt even if the same writes would now be rejected, and a dry run of a retry SHALL report that it would be committed. WHEN the key was used with different content, THE SYSTEM SHALL reject the transaction with that one problem, located at `@transaction`. WHEN the transaction that used the key wrote data that a purge has since removed, or named an entity that a purge has since removed, by its ID or by a unique key, THE SYSTEM SHALL reject the transaction, and a dry run of it, with that one problem, located at `@transaction`, whatever its content (capability `purge`).

#### Scenario: Retrying a committed transaction

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and the same transaction is submitted again
- **THEN** the second submission returns the first receipt, and no second transaction is recorded

#### Scenario: Reusing a key for other writes

- **WHEN** a transaction with the idempotency key `onboard-acme` is committed, and a transaction with the same key but other writes is submitted
- **THEN** the second transaction is rejected with a problem

#### Scenario: Retrying writes that are now invalid

- **WHEN** a transaction with the idempotency key `onboard-acme` created ACME with the LEI `5493001KJTIIGC8Y1R12`, a later transaction gave Gamma that LEI after ACME's LEI was removed, and the first transaction is submitted again
- **THEN** the submission returns the first receipt, and nothing is recorded

#### Scenario: A retry with another clearance

- **WHEN** a transaction with the idempotency key `fund-a1` and the clearance `finance` is committed, and the same transaction is submitted again without a clearance
- **THEN** the second submission is rejected with the problem for a key used with different content, located at `@transaction`
