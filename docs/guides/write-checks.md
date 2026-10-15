---
last-reviewed: 2026-10-15
owner: ""
---

# Write checks

How to declare *checks*, short expressions in the schema document that every version of a type's values must satisfy, such as "the last day of a leave is not before the first".
Checks are the third level of validation, between declarative constraints and Java rules ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)): people and agents write them at runtime, without deploying code.
They are written in the [Common Expression Language](https://cel.dev/) (CEL). Why they work this way is in [ADR-0030](../decisions/0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md); what they guarantee is in the `checks` spec.

## Add the module

Checks run in the module `cairn-cel` (`io.github.enricoag1982.cairn.cel`), which uses Google's CEL for Java and brings its dependencies: Guava, protobuf-java, the ANTLR runtime and a few smaller ones.
Put it on the module path or class path of the application; stores and schema registries find it through `ServiceLoader`, or take it from `StoreSettings.builder().expressionLanguage(...)`.
The agent server includes it. Without it, a registry rejects definitions that declare checks (`CHECKS_UNAVAILABLE`), and a store whose schema has checks rejects writes to the types they apply to.

## Declare a check

```yaml
entityTypes:
  LeaveRequest:
    attributes:
      firstDay: { type: date, required: true }
      lastDay: { type: date, required: true }
    checks:
      leave-dates-in-order:
        expression: self.lastDay >= self.firstDay
        message: The last day is before the first day.
```

- A check has a name, written like a rule name (lowercase letters, digits and hyphens), an expression, and an optional message for the problem it reports.
- Entity types and relationship types declare checks; subtypes inherit them. A name is declared once in a hierarchy.
- In Java, `EntityTypeBuilder.check(name, expression, message)` declares the same.

## What an expression sees

One variable, `self`: the values of the version being checked, by attribute name. An optional attribute without a value is absent from `self`, so test it first:

```text
!has(self.birthDate) || self.birthDate < timestamp('2026-01-01T00:00:00Z')
```

| Attribute type | In CEL |
| --- | --- |
| `string`, and attributes with allowed values | string |
| `boolean` | bool |
| `int`, `long` | int |
| `decimal` | double, so `0.0`, not `0`, in comparisons |
| `date` | timestamp at 00:00 UTC of that day |
| `instant` | timestamp |

A check sees only that version: not other versions, other entities, or the ends of a relationship. Checks that need more are Java rules.
Decimals become doubles, which are not exact: keep exact decimal comparisons, such as shares that must add up, in Java rules.

## When checks run

- **At registration**, the registry compiles every check. An expression that does not compile, names an attribute the type does not have, or cannot give a boolean is a problem `INVALID_CHECK` at the check. In a document, the problem is located at the check's `expression`, with the position in it.
- **At commit**, checks run on every segment of a timeline that the transaction writes, with the declarative constraints and before Java rules. A false result rejects the transaction with `CHECK_VIOLATED`, the check's name and its message; an evaluation error, such as reading an absent attribute without `has`, or an expression too costly to run, rejects it with `CHECK_FAILED`. Dry runs report the same problems.

## Changing checks

Adding a check to an existing type, or changing its expression, is a breaking change: data that the store already holds might not satisfy it. Removing a check, or changing only its message, is compatible (see the `schema-evolution` spec).
