---
last-reviewed: 2026-10-16
owner: ""
---

# Derive attributes

How to declare *derived attributes*, whose values a CEL expression computes from the other values of the same version, such as a person's full name from the given and family names.
Why they work this way is in [ADR-0031](../decisions/0031-derive-attributes-from-a-version-s-own-values-in-cel-computed-at-commit-and-stored.md); what they guarantee is in the `derived-attributes` spec.
They use the same expression language as checks, so read [Write checks](write-checks.md) first: the module `cairn-cel`, what `self` holds, and how attribute types map to CEL.

## Declare one

```yaml
entityTypes:
  Person:
    attributes:
      givenName: { type: string, required: true }
      familyName: { type: string, required: true }
      fullName:
        type: string
        required: true
        derived: self.givenName + ' ' + self.familyName
```

- Any attribute of an entity type, relationship type or trait can be derived. It keeps its other properties: required, unique, ranges, patterns and the rest apply to the computed value.
- The expression sees `self` with the attributes that are not derived. It cannot read another derived attribute, another version, or another entity.
- In Java, `AttributeBuilder.derived(expression)` declares the same.
- Expressions can call [functions of Cairn's own](write-checks.md#functions-of-cairns-own), such as `cairn.workingDays`: a leave's days can be `double(cairn.workingDays(self.firstDay, self.lastDay))`.

## What a derivation must give

The result must fit the attribute's type:

| Attribute type | CEL result |
| --- | --- |
| `string`, and attributes with allowed values | string |
| `boolean` | bool |
| `int` | int, within the range of a Java `int` |
| `long` | int |
| `decimal` | double, taken at its shortest decimal form |
| `instant` | timestamp |
| `date` | timestamp at 00:00 UTC |

An optional derived attribute has no value when the expression gives `null`, for example `has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null`. CEL's checker accepts `null` beside an integer, a string, a boolean or a double only when that branch is wrapped in `dyn(...)`; timestamps need no wrapping.

## When values are computed

At commit, for every segment of a timeline that the transaction writes, before constraints, checks and rules, which all see the derived value. The value is stored with the version: views, queries, unique constraints and the change feed read it like any other value, and a view as of an older basis reads the value derived then.

- A write that sets or clears a derived attribute is rejected with `DERIVED_ATTRIBUTE_WRITTEN`. Creates need not give derived attributes, even required ones. Generated payload schemas leave them out of writes, and generated Java builders have no setter for them.
- A result that does not fit, an evaluation error, or no value for a required derived attribute rejects the transaction with `DERIVATION_FAILED`.
- An expression that does not compile, reads a derived or unknown attribute, or gives the wrong type is a problem `INVALID_DERIVATION` at registration.

## Changing derived attributes

Values are stored, so a new expression would leave older versions with values it would not give. Adding a derived attribute to an existing type, making an attribute derived, or changing an expression is therefore a breaking change; declare derived attributes with their type. Making a derived attribute plain is compatible, and its stored values stay.

## Limits

- Decimal results pass through doubles, so they are only as exact as a double.
- Values that depend on other entities, such as days of leave taken in a year, or working days without public holidays from a calendar, are queries or rules, not derived attributes.
