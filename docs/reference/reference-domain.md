---
last-reviewed: 2026-10-12
owner: ""
---

# Reference domain

A small, made-up enterprise model used for spec scenarios, the conformance suite, examples and benchmarks.
It is not product scope: none of its names (Party, Account, Owns) may appear in the engine, the API or normative requirement text. Scenarios use them as examples.
It exists because sysRec has no product domain yet, and abstract requirements become testable once they are about concrete data.
Why it looks like this: [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Model

```text
 trait Named { name }

              +---------------------------+
              | Party (abstract), Named   |
              +---------------------------+
                 ^                     ^
                 | extends             | extends
          +------+------+       +------+---------+
          | Person      |       | Organization   |
          +-------------+       +----------------+

 Relationships
   Organization --Owns {share}----------> Party          many-to-many, recursive
   Organization --Employs {title}-------> Person         many-to-many
   Account      --HeldBy----------------> Party          exactly one per account at any time
   Party        <>-HasAddress-----------> Address        composition, one-to-many
   CustomerRole --PlayedBy--------------> Party          many-to-one (a role)
```

| Type | Kind | Attributes | Notes |
| --- | --- | --- | --- |
| `Named` | trait | `name: String`, required | |
| `Party` | abstract entity type | from `Named` | |
| `Person` | entity type, extends `Party` | `birthDate: Date` | |
| `Organization` | entity type, extends `Party` | `lei: String`, unique | Legal Entity Identifier |
| `Address` | entity type | `street`, `city`, `country: String` | Exists only through `HasAddress` |
| `Account` | entity type | `number: String`, unique; `currency: String`; `balance: Decimal` | Small, so frequent balance changes are cheap |
| `CustomerRole` | entity type | `segment: String` | A role, linked by `PlayedBy` |
| `Owns` | relationship type, association | `share: Decimal`, required, `0 < share <= 100` | Inverse `ownedBy` |
| `Employs` | relationship type, association | `title: String` | Inverse `employedBy` |
| `HeldBy` | relationship type, association | none | Inverse `holds` |
| `HasAddress` | relationship type, composition | none | Inverse `party` |
| `PlayedBy` | relationship type, association | none | Inverse `roles` |

The **ultimate parent** of a party is found by following `ownedBy` while the share is above 50%, until no owner remains.

## Stories

Scenarios reuse these stories and cite them by ID. Dates are in 2026 unless stated; "recorded" is the transaction time.

| ID | Story | What it exercises |
| --- | --- | --- |
| RD-1 | Recorded 5 January: ACME and Beta (organizations) and Alice (a person) exist from 1 January. | Creating entities, inheritance |
| RD-2 | Recorded 10 February: ACME owns 60% of Beta from 1 February. Recorded 2 May: correction, the share was 55% from 1 February. | Relationships with attributes, corrections |
| RD-3 | Recorded 1 March: Beta employs Alice as "Analyst" from 1 March. | Relationships to a subtype |
| RD-4 | Recorded 1 March: Beta is a customer, segment "Corporate", from 1 March until 1 October. | Roles, closed periods |
| RD-5 | Account A-1 is held by Beta and A-2 by ACME, both from 1 January, with balances 1,000 and 2,000. Recorded 16 March: A-1's balance is 1,500 from 15 March. Recorded 20 April: correction, A-1's balance was 1,200 from 15 March. | Patch, corrections, aggregation |
| RD-6 | Total balance by ultimate parent, effective 31 March: as known on 5 April, ACME's group has 3,500; as known now, 3,200. | Two time axes, recursive traversal, aggregation |
| RD-7 | Recorded 12 October: ACME is renamed "ACME Holdings" from 1 January 2027. Effective 31 December 2026 the name is "ACME"; effective 1 January 2027 it is "ACME Holdings". | Future-dated changes |
| RD-8 | Ending Beta from 1 June is rejected while relationships to Beta continue after that date, such as A-1's `HeldBy`. It succeeds when the same transaction also ends them (`HeldBy`, `Owns`, `Employs`, `PlayedBy`) from 1 June. | Relationship lifetimes within their endpoints' |
| RD-9 | Creating another organization with ACME's LEI is rejected while ACME's LEI is effective. | Uniqueness over time |
| RD-10 | Effective 31 March, querying `Party` returns ACME, Beta and Alice; querying `Organization` returns ACME and Beta. | Polymorphic queries |
| RD-11 | Purging Alice removes her facts from all of history and leaves a record that a purge happened. | Purge |

## Datasets

A generator for larger datasets of the same model (for example 10 million parties) is planned for benchmarks. It does not exist yet.
