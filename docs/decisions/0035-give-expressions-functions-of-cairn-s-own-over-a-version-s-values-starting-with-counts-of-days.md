---
status: accepted
date: 2026-10-16
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Give expressions functions of Cairn's own over a version's values, starting with counts of days

## Context and Problem Statement

Derived attributes and checks are CEL expressions over one version's own values ([ADR-0030](0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md), [ADR-0031](0031-derive-attributes-from-a-version-s-own-values-in-cel-computed-at-commit-and-stored.md)). The [HR domain](../reference/hr-domain.md) enters a leave's `days` by hand, because counting the working days between two dates is not something standard CEL can express; the page lists it as the gap "Derived values from other data".
Two ways close it: derived values computed by Java functions that read other data, such as a holiday calendar, or functions of Cairn's own that expressions call over the version's values. Once documents call a function, its name and meaning are part of the schema language, so the choice is costly to change. The change `add-expression-functions` raised it.

## Decision Drivers

- A derived value depends only on its own version, so it never needs computing again and history stays reproducible (ADR-0031)
- No write changes an entity that the transaction does not name, beyond composition cascades
- People and agents declare such values in the schema document, without deploying code
- The same result with every storage implementation, and on every machine, whatever its time zone

## Considered Options

- Functions of Cairn's own in the expression language, pure functions of their arguments, starting with counts of days between two dates
- Derived values computed by named Java functions that may read other data, stored, and computed again when what they read changes
- Derived values computed by named Java functions on read, never stored

## Decision Outcome

Chosen option: "Functions of Cairn's own in the expression language", because it closes the HR need within the rules of ADR-0031, with no new kind of write and nothing to recompute.

- **The functions.** The expression language offers, in checks and derivations alike, `cairn.calendarDays(first, last)` and `cairn.workingDays(first, last)`: the number of days, or of days from Monday to Friday, from the first to the last date, both included, as integers. Their arguments are timestamps, as dates are in CEL ([ADR-0030](0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md)), and they count by the dates in UTC, so the result never depends on the machine's time zone. A last date before the first gives 0.
- **Pure.** Cairn's functions read only their arguments: no clock, no other data, no settings. An expression that calls them still depends only on its own version.
- **Names.** Cairn's functions are named `cairn.<name>`, so that they never clash with functions that CEL adds later. A name that the language does not offer is a compilation problem, as today.
- **Evolution.** A function, once offered, keeps its name and meaning; a different meaning gets a new name.
- **What stays open.** Values that depend on other entities, such as working days without public holidays from a calendar, stay queries or rules. A calendar of holidays as data can follow, with its own decision.

### Consequences

- Good, because the HR example derives a leave's days from its dates, so they cannot disagree.
- Good, because derived values keep every property of ADR-0031: stored, reproducible and never computed again.
- Bad, because working days count Monday to Friday everywhere: public holidays and other working weeks are not counted.
- Bad, because every function that Cairn offers is a promise to keep, so the library grows slowly.

### Confirmation

- Scenario tests count days in a check and in a derivation, including a last day before the first and dates around a weekend.
- The HR example derives `days` with `cairn.workingDays`.

## Pros and Cons of the Options

### Java functions that read other data, computed again when it changes

- Good, because any value can be derived, holidays included.
- Bad, because a change to what a function read writes new versions of entities that the transaction does not name, in the past as well as now (ADR-0031).

### Java functions on read

- Good, because nothing is stored and nothing is computed again.
- Bad, because queries, constraints and uniqueness cannot use the values without computing them first, and history read at an older basis depends on the code that runs now.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, and their go-ahead of 2026-10-16 to take the gaps that remained open.
- It adds to ADR-0030 and ADR-0031 what expressions may call; both stay in force.
