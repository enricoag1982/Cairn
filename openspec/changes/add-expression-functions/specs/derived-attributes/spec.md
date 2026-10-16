## MODIFIED Requirements

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
