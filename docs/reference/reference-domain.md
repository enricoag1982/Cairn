---
last-reviewed: 2026-10-13
owner: ""
---

# Reference domain

A small, made-up enterprise model used for spec scenarios, the conformance suite, examples and benchmarks.
It is not product scope: none of its names (Party, Account, Owns) may appear in the engine, the API or normative requirement text. Scenarios use them as examples.
It exists because Cairn has no product domain yet, and abstract requirements become testable once they are about concrete data.
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

 Relationships (targets per source / sources per target)
   Organization --Owns {share}-----> Party      0..* / 0..*   recursive
   Organization --Employs {title}--> Person     0..* / 0..*
   Account      --HeldBy-----------> Party      1    / 0..*
   Party        <>-HasAddress------> Address    0..* / 1      composition
   CustomerRole --PlayedBy---------> Party      1    / 0..*   a role
```

Entity types and traits:

| Type | Kind | Attributes |
| --- | --- | --- |
| `Named` | trait | `name: string`, required |
| `Party` | abstract entity type, includes `Named` | none of its own |
| `Person` | entity type, extends `Party` | `birthDate: date`, optional |
| `Organization` | entity type, extends `Party` | `lei: string`, optional, unique, pattern `[A-Z0-9]{18}[0-9]{2}` (Legal Entity Identifier) |
| `Address` | entity type | `street: string`, required; `city: string`, required; `country: string`, required |
| `Account` | entity type | `number: string`, required, unique; `currency: string`, required, pattern `[A-Z]{3}`; `balance: decimal`, required |
| `CustomerRole` | entity type | `segment: string`, required, one of `Corporate`, `Retail` |

`Account` is kept small, so frequent balance changes stay cheap ([ADR-0009](../decisions/0009-version-whole-entities-rather-than-single-attributes.md)).

Relationship types. Multiplicities are written as minimum..maximum, with `*` for unbounded:

| Type | Source | Target | Targets per source | Sources per target | Kind | Attributes | Inverse name |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Owns` | `Organization` | `Party` | 0..* | 0..* | association | `share: decimal`, required, 0 (exclusive) to 100 (inclusive) | `ownedBy` |
| `Employs` | `Organization` | `Person` | 0..* | 0..* | association | `title: string`, optional | `employedBy` |
| `HeldBy` | `Account` | `Party` | 1..1 | 0..* | association | none | `holds` |
| `HasAddress` | `Party` | `Address` | 0..* | 1..1 | composition | none | `party` |
| `PlayedBy` | `CustomerRole` | `Party` | 1..1 | 0..* | association | none | `roles` |

Rules ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)), each with the problem codes it reports:

| Rule | Version | Type | Description | Problem codes |
| --- | --- | --- | --- | --- |
| `lei-checksum` | 1 | `Organization` | The LEI's check digits are valid (ISO 17442, ISO 7064 MOD 97-10). | `invalid-check-digits` |
| `total-ownership-at-most-100` | 1 | `Owns` | The owners of a party hold at most 100% of it together, at every point in effective time. | `over-100-percent` |

The **ultimate parent** of a party is found by following `ownedBy` while the share is above 50%, until no owner remains.

## Stories

Scenarios reuse these stories and cite them by ID. Dates are in 2026 unless stated; "recorded" is the transaction time.

| ID | Story | What it exercises |
| --- | --- | --- |
| RD-1 | Recorded 5 January: ACME and Beta (organizations) and Alice (a person) exist from 1 January. | Creating entities, inheritance |
| RD-2 | Recorded 10 February: ACME owns 60% of Beta from 1 February. Recorded 2 May: correction, the share was 55% from 1 February. | Relationships with attributes, corrections |
| RD-3 | Recorded 1 March: Beta employs Alice as "Analyst" from 1 March. | Relationships to a subtype |
| RD-4 | Recorded 1 March: Beta is a customer, segment "Corporate", from 1 March until 1 October. | Roles, closed periods |
| RD-5 | Recorded 5 January: account A-1 is held by Beta and A-2 by ACME, both from 1 January, with balances 1,000 and 2,000. Recorded 16 March: A-1's balance is 1,500 from 15 March. Recorded 20 April: correction, A-1's balance was 1,200 from 15 March. | Patch, corrections, aggregation |
| RD-6 | Total balance by ultimate parent, effective 31 March: as known on 5 April, ACME's group has 3,500; as known now, 3,200. | Two time axes, recursive traversal, aggregation |
| RD-7 | Recorded 12 October: ACME is renamed "ACME Holdings" from 1 January 2027. Effective 31 December 2026 the name is "ACME"; effective 1 January 2027 it is "ACME Holdings". | Future-dated changes |
| RD-8 | Ending Beta from 1 June is rejected while relationships to Beta continue after that date, such as A-1's `HeldBy`. It succeeds when the same transaction, from 1 June, moves A-1 to ACME (ends `HeldBy` Beta and starts `HeldBy` ACME, keeping exactly one holder), ends `Owns` and `Employs` involving Beta, and ends Beta's customer role together with its `PlayedBy`. | Relationship lifetimes within their endpoints', multiplicities over time |
| RD-9 | Creating another organization with ACME's LEI is rejected while ACME's LEI is effective. | Uniqueness over time |
| RD-10 | Effective 31 March, querying `Party` returns ACME, Beta and Alice; querying `Organization` returns ACME and Beta. | Polymorphic queries |
| RD-11 | Purging Alice removes her facts from all of history and leaves a record that a purge happened. | Purge |

## Datasets

A generator for larger datasets of the same model (for example 10 million parties) is planned for benchmarks. It does not exist yet.
