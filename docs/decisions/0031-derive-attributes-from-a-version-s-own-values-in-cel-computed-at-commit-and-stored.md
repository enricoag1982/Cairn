---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Derive attributes from a version's own values in CEL, computed at commit and stored

## Context and Problem Statement

Some attributes follow from others: the [HR domain](../reference/hr-domain.md) has a person's full name composed by every caller from the given and family names. [ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md) expected derived attributes later, as named Java functions; [ADR-0025](0025-run-a-rule-again-when-the-data-it-read-changes.md) offered its record of reads to tell them when to compute again; [ADR-0030](0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md) gave the schema document expressions in CEL over one version.
Whether a derived value is stored or computed on read, what it may read, and how it evolves decide what history and queries return, so they are costly to change. The change `add-derived-attributes` raised it.

## Decision Drivers

- History reproduces what was known: a value read as of a basis is the value that was derived then, even after the expression changes ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md))
- Derived values are read, filtered, aggregated and constrained like any other value, in every kind of store
- People and agents declare them in the schema document at runtime, as they declare checks
- No write ever changes an entity that the transaction does not name, beyond what composition cascades already do

## Considered Options

- CEL expressions over the version's own values, computed at commit for every written segment and stored as the attribute's value
- The same expressions, computed when a version is read and never stored
- Named Java functions that may read other data, recomputed when what they read changes

## Decision Outcome

Chosen option: "CEL expressions over the version's own values, computed at commit and stored", because it is the only option that keeps history reproducible and derived values queryable without writes to entities that a transaction does not name.

- **What a derived attribute is.** An attribute of an entity type, relationship type or trait MAY be derived: it states a CEL expression instead of taking values from writes. The expression sees `self` as checks do, with the attributes that are not derived; it does not see other derived attributes, versions, entities or relationships.
- **When it is computed.** At commit, for every segment of a timeline that the transaction writes, before constraints, checks and rules, which see the derived value. The value is stored with the version, so views, queries, uniqueness, the change feed and every kind of store treat it as any other value.
- **Its value.** The result must fit the attribute's value type: a CEL string, boolean, integer (in range for `int`), double for `decimal` (taken at its shortest decimal form), or timestamp for `instant` and, at the start of a day in UTC, for `date`. An optional derived attribute has no value when the expression gives `null`. A result that does not fit, or an evaluation error, rejects the transaction with a problem of its own.
- **Writes.** A write that sets a derived attribute is rejected. Generated values schemas mark it read-only, payload schemas leave it out of writes, and generated builders have no setter for it.
- **Schema evolution.** Adding a derived attribute to an existing type, making an attribute derived or changing a derived attribute's expression is a breaking change, because versions already stored would not hold what the expression gives; making a derived attribute plain is compatible, and its stored values stay.
- **The expression language.** Derived attributes need the module `cairn-cel`, as checks do; without it a registry rejects them and a store rejects writes to their types, with one problem code for both.

### Consequences

- Good, because a derived value read as of any basis is the one derived then, and queries and constraints use it with no special case.
- Good, because a derived value depends only on its own version, so it never needs computing again after the commit that wrote the version.
- Good, because agents declare derived values in the document, as they declare checks.
- Bad, because a value that depends on other entities, such as days of leave taken in a year, cannot be derived; it stays a query or a rule.
- Bad, because changing an expression is a breaking change until migrations exist.
- Bad, because decimal results pass through doubles, so derived decimals are only as exact as a double.

### Confirmation

- Scenario tests derive values on creation and on every later write, reject writes that set them, reject results that do not fit, and read them through views and queries in every kind of store.
- The HR example derives a person's full name.

## Pros and Cons of the Options

### Computed on read

- Good, because nothing is stored and changing the expression costs nothing.
- Bad, because changing the expression changes what history returns, against ADR-0008, and queries must compute values before they can filter or aggregate them.

### Named Java functions that read other data

- Good, because any value can be derived.
- Bad, because a change to the data a function read must write new versions of entities that the transaction does not name, in the past as well as now.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again; derived attributes were in the roadmap the user agreed to on 2026-10-15, with the proposal of `add-derived-attributes`.
- It replaces, for derived attributes, the sentence of ADR-0018 that they follow as named Java functions; values that need other data can follow as such, with recomputation through the reads of ADR-0025.
- Derived attributes that see other derived attributes, and migrations that derive values for versions already stored, can follow.
- Revised on 2026-10-16, before `add-derived-attributes` was archived, after its drift review: traits may declare derived attributes, it is the values schemas that mark them read-only while payload schemas leave them out, and the sentence of ADR-0018 that this decision replaces is in its Decision Outcome. Schema records keep derived attributes in an optional trailing section of format version 1, as they keep checks (ADR-0030).
- Expressions may call functions of Cairn's own, pure functions of their arguments, as [ADR-0035](0035-give-expressions-functions-of-cairn-s-own-over-a-version-s-values-starting-with-counts-of-days.md) decides.
