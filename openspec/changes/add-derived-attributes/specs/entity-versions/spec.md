## MODIFIED Requirements

### Requirement: Replacing entity state

A replace SHALL set the complete state of an entity for an effective period: attributes it does not mention SHALL have no value in that period, except derived attributes, which are computed (see the `derived-attributes` capability).

#### Scenario: Replacing a person's state

- **WHEN** Alice has a name and a birth date, and a replace from 1 June sets only her name
- **THEN** a view effective at 1 June shows Alice without a birth date, and a view effective at 31 May shows her birth date

#### Scenario: Replacing an account's state

- **WHEN** account A-1 has a number, a currency and a balance, and a replace from 1 June sets only its number and balance
- **THEN** the transaction is rejected, because the required attribute `currency` would have no value
