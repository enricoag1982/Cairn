---
last-reviewed: 2026-10-16
owner: ""
---

# Write checks

How to declare *checks*, short expressions in the schema document that every version of a type's values must satisfy, such as "the last day of a leave is not before the first".
Checks are the third level of validation, between declarative constraints and Java rules ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)): people and agents write them at runtime, without deploying code.
They are written in the [Common Expression Language](https://cel.dev/) (CEL). Why they work this way is in [ADR-0030](../decisions/0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md); what they guarantee is in the `checks` spec. The same expressions also compute [derived attributes](derive-attributes.md).

## Add the module

Checks run in the module `cairn-cel`, which uses Google's CEL for Java and brings about 8 MB of dependencies: Guava, protobuf-java, the ANTLR runtime and a few smaller ones.
Put it on the class path of the application: CEL's jars cannot be named modules, but Cairn's modules on the module path still find it through `ServiceLoader`. `StoreSettings.builder().expressionLanguage(...)` sets a language explicitly, and `noExpressionLanguage()` none.
Without it, a registry rejects definitions that declare checks (`EXPRESSIONS_UNAVAILABLE`), and a store whose schema has checks rejects writes to the types they apply to.
The agent server includes it. The code generator validates documents, so a build whose documents declare checks adds it to the generator's class path too, with `cairnCodegen("io.github.enricoag1982.cairn:cairn-cel:0.1.0-SNAPSHOT")` next to the generator ([Generate Java types](generate-java-types.md)).
On Java 25, protobuf-java prints a warning about `sun.misc.Unsafe` when it loads; it does no harm.

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
- Entity types and relationship types declare checks; subtypes inherit them. A name is declared once in a hierarchy, and must differ from the names of the type's attributes and rules, and those of its supertypes, because problems name all of them as `Type.name`.
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
| `decimal` | double, so compare with `0.0`, not `0`: CEL rejects mixing integers and doubles |
| `date` | timestamp at 00:00 UTC of that day |
| `instant` | timestamp |

A check sees only that version: not other versions, other entities, or the ends of a relationship. Checks that need more are Java rules.
Decimals become doubles, which are not exact: keep exact decimal comparisons, such as shares that must add up, in Java rules.

## Functions of Cairn's

Besides CEL's standard functions, expressions can call functions of Cairn's own, named `cairn.<name>` ([ADR-0035](../decisions/0035-give-expressions-functions-of-cairn-s-own-over-a-version-s-values-starting-with-counts-of-days.md)). They read nothing but their arguments, so checks and derivations that call them still depend only on their version.

| Function | Gives |
| --- | --- |
| `cairn.calendarDays(first, last)` | The number of days from the date of `first` to the date of `last`, both included, as an int; 0 if `last` is before `first` |
| `cairn.workingDays(first, last)` | The same, counting only Mondays to Fridays |

Both take timestamps, such as dates, and count by their dates in UTC, so the result is the same on every machine. `cairn.workingDays(self.firstDay, self.lastDay)` is 5 for a leave from Monday to Friday. Public holidays are not counted out: they need calendar data, which a version's values do not hold.
A call with arguments of other types is a problem `INVALID_CHECK`, or `INVALID_DERIVATION` in a derivation, located at the call. A name that Cairn does not offer, such as a misspelled `cairn.workDays`, is reported by CEL as an undeclared reference to `cairn`.

## When checks run

- **At registration**, the registry compiles every check. An expression that does not compile, names an attribute the type does not have, or cannot give a boolean is a problem `INVALID_CHECK` at the check. In a document, the problem is located at the check's `expression`, with the position in it.
- **At commit**, checks run on every segment of a timeline that the transaction writes, with the declarative constraints and before Java rules. A false result rejects the transaction with `CHECK_VIOLATED`, the check's name and its message; an evaluation error, such as reading an absent attribute without `has`, or an expression that takes more than 10,000 evaluation steps, rejects it with `CHECK_FAILED`. Dry runs report the same problems.

## Changing checks

Adding a check to an existing type, or changing its expression, is a breaking change: data that the store already holds might not satisfy it. Removing a check, or changing only its message, is compatible (see the `schema-evolution` spec).
