# derived-attributes Specification

## Purpose
Defines derived attributes: attributes whose values an expression in the Common Expression Language computes from the other values of the same version at commit, and which are stored with the version; how they are declared and compiled, computed, kept out of writes, and shown in generated JSON Schemas and Java types.

## Requirements

### Requirement: Derived attributes

An attribute of an entity type, relationship type or trait MAY be derived: it states an expression in the Common Expression Language (CEL) instead of taking values from writes. The expression SHALL see `self` as checks do (see the `checks` capability), with the attributes of the version that are not derived, and SHALL be able to call the functions of Cairn's own that checks can call. A derived attribute SHALL keep the other properties of attributes, and its constraints SHALL apply to the computed value. THE SYSTEM SHALL compile every derived attribute when a definition is validated, planned or registered, for each type that has it: an expression that does not compile, that reads through `self` an attribute that is derived or that the type does not have, or whose result cannot be a value of the attribute's type, SHALL be a problem of the code `INVALID_DERIVATION` at the attribute. A registry without an expression language SHALL reject a definition that declares derived attributes with a problem of the code `EXPRESSIONS_UNAVAILABLE` at each of them.

#### Scenario: A derived label

- **WHEN** Account declares the derived string attribute `label` with the expression `self.number + ' ' + self.currency`, and the definition is validated with an expression language
- **THEN** validation succeeds, and the schema reports `label` as derived, with its expression

#### Scenario: A derivation that reads a derived attribute

- **WHEN** Account declares the derived attribute `label`, and the derived string attribute `shout` with the expression `self.label + '!'`
- **THEN** validation fails with a problem of the code `INVALID_DERIVATION` located at `Account.shout`, which names `label`

#### Scenario: A derivation of the wrong type

- **WHEN** Account declares the derived int attribute `cents` with the expression `self.balance * 100.0`
- **THEN** validation fails with a problem of the code `INVALID_DERIVATION` located at `Account.cents`

### Requirement: Derived values are computed at commit

WHEN a transaction writes a segment of the timeline of an entity or relationship, THE SYSTEM SHALL compute each of its derived attributes from that segment's other values, before value constraints, checks and rules, and SHALL store the results with the version, so that views, queries, uniqueness and the change feed read them as any other value. A result SHALL fit the attribute's value type: a CEL string, boolean, integer within the range of the type, double for a decimal, or timestamp for an instant and, at the start of a day in UTC, for a date. An optional derived attribute SHALL have no value when its expression gives `null`. An evaluation error, a result that does not fit, or no value for a required derived attribute SHALL reject the transaction with a problem of the code `DERIVATION_FAILED` at the write. A store whose schema has derived attributes, opened without an expression language, SHALL reject writes to their types with a problem of the code `EXPRESSIONS_UNAVAILABLE`, and a recorded derivation that the store's expression language cannot compile when the store is opened SHALL make writes to its types fail with `DERIVATION_FAILED`.

#### Scenario: Derived on creation

- **WHEN** Account declares the derived attribute `label`, and the transactions of RD-5 are committed
- **THEN** A-1 reads with the label `A-1 EUR`

#### Scenario: Derived again for every written segment

- **WHEN** Account declares the derived long attribute `balanceInCents` with the expression `int(self.balance * 100.0)`, and the transactions of RD-5 are committed
- **THEN** effective 31 March, A-1 reads with 120000 as known now and with 150000 as known on 16 April, and effective 31 January with 100000

#### Scenario: An optional derived attribute without a value

- **WHEN** Person declares the derived optional int attribute `birthYear` with the expression `has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null`, and Alice is created without a birth date and Bob with the birth date 1990-05-01
- **THEN** Alice reads without a birth year, and Bob with 1990

#### Scenario: A derived value that does not fit

- **WHEN** Account declares the derived int attribute `balanceInBillions` with the expression `int(self.balance) * 10000000`, and A-1 is created with the balance 1,000
- **THEN** the transaction is rejected with a problem of the code `DERIVATION_FAILED` that names `balanceInBillions`

#### Scenario: A query on a derived value

- **WHEN** Account declares the derived attribute `label`, the transactions of RD-5 are committed, and a query lists the accounts whose label is `A-2 EUR`, effective 31 March
- **THEN** the result is A-2

### Requirement: Derived attributes are not written

A write SHALL NOT set a derived attribute: a create, relate, patch, replace or clear that names one SHALL be rejected with a problem of the code `DERIVED_ATTRIBUTE_WRITTEN` at the write, located in the payload when the transaction was read from one. A create SHALL NOT need to give a derived attribute, even a required one. The generated JSON Schema of a type's values SHALL mark derived attributes as read-only, and the generated JSON Schema of transaction payloads SHALL NOT accept them in the values of writes. Generated Java builders of writes SHALL have no setter for a derived attribute, and generated snapshot records SHALL read it.

#### Scenario: Setting a derived attribute

- **WHEN** Account declares the derived attribute `label`, the transactions of RD-5 are committed, and a payload patches A-1 with the label `mine`
- **THEN** the transaction is rejected with a problem of the code `DERIVED_ATTRIBUTE_WRITTEN` located at the label in the payload

#### Scenario: A derived attribute in the payload schema

- **WHEN** Account declares the derived attribute `label`, and the JSON Schema of transaction payloads is generated
- **THEN** a payload that creates an account without a label is valid against it, and one that sets the label is not

#### Scenario: A derived attribute in generated Java types

- **WHEN** Java types are generated for a document in which Account declares the derived attribute `label`
- **THEN** the builder of Account's writes has no setter for `label`, and Account's snapshot record has `label`
