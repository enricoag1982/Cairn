## ADDED Requirements

### Requirement: Unique keys

A write SHALL be able to name an existing entity by a *unique key*: an entity type, an attribute that is unique in that type, and a value. THE SYSTEM SHALL resolve a key at the start of the write's effective period, against the store as committed before the transaction, to the entity of that type, or of one of its subtypes, that has the value then, and SHALL then treat the write as if it named that entity's ID: problems about the entity SHALL name it by its ID. A key MUST name an entity type of the schema, an attribute of that type, or an alias of one, that is unique, and a value that fits the attribute's value type; otherwise THE SYSTEM SHALL reject the transaction with a problem of the code for invalid keys, which lists the unique attributes of the type where it has some. WHEN no entity has a valid key at that time, THE SYSTEM SHALL reject the transaction with a problem of the code for keys that name no entity. Both problems SHALL have the path of the write (`@write.` followed by its position, counted from 0) and SHALL name the key in their message. The transaction as submitted, which idempotency keys compare and durable stores record, SHALL keep its keys.

#### Scenario: Patching an account by its number

- **WHEN** A-1 exists with the balance 1,000 (RD-5), and a transaction patches the Account with the number `A-1` to the balance 1,500 from 15 March
- **THEN** the transaction is committed, and A-1's balance effective 31 March is 1,500

#### Scenario: Relating by a key

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` from 1 January (RD-9), and a transaction relates the Organization with that LEI to Alice with Employs, from 1 March
- **THEN** the transaction is committed, and ACME employs Alice effective 1 March

#### Scenario: A key whose value moved

- **WHEN** ACME has the LEI `5493001KJTIIGC8Y1R12` until 1 March, Gamma has it from 1 March, and a transaction patches the Organization with that LEI to the name "Gamma Holdings" from 1 April
- **THEN** the transaction is committed, Gamma's name effective 1 April is "Gamma Holdings", and ACME's name is unchanged

#### Scenario: A key that names no entity

- **WHEN** a transaction's first write patches the Account with the number `A-9`, which no account has
- **THEN** the transaction is rejected with a problem of the code for keys that name no entity, at the path `@write.0`

#### Scenario: A key on an attribute that is not unique

- **WHEN** a transaction patches the Account with the currency `EUR`
- **THEN** the transaction is rejected with a problem of the code for invalid keys, whose expected value names `number`
