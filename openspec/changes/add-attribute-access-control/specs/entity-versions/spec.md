## MODIFIED Requirements

### Requirement: Replacing entity state

A replace SHALL set the complete state of an entity for an effective period: attributes it does not mention SHALL have no value in that period, except derived attributes, which are computed (see the `derived-attributes` capability), and, in a transaction with a clearance, attributes that the clearance does not cover, whose values carry forward (see the `attribute-access` capability).

#### Scenario: Replacing a person's state

- **WHEN** Alice has a name and a birth date, and a replace from 1 June sets only her name
- **THEN** a view effective at 1 June shows Alice without a birth date, and a view effective at 31 May shows her birth date

#### Scenario: Replacing an account's state

- **WHEN** account A-1 has a number, a currency and a balance, and a replace from 1 June sets only its number and balance
- **THEN** the transaction is rejected, because the required attribute `currency` would have no value

#### Scenario: A replace that leaves out a withheld balance

- **WHEN** Account's `balance` is classified `finance`, the transactions of RD-1 and RD-5 are recorded, and a transaction with no classification in its clearance replaces A-1's state from 1 May with its number and currency
- **THEN** the transaction is committed, and A-1's balance on 1 May is still 1,200 as read through a view that is not restricted
