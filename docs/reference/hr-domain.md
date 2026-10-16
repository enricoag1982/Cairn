---
last-reviewed: 2026-10-16
owner: ""
---

# HR domain

A basic HR system for a fictional company, Brightwater, built on Cairn as an application would be: the end-to-end test of the platform.
Why it looks like this is in the [research note](../research/2026-10-13-hr-system-as-a-test-domain.md). The code lives in the subproject `cairn-example-hr`, with its schema document in `cairn-example-hr/src/main/cairn/hr-model.cairn.yaml`.
The [reference domain](reference-domain.md) stays the engine's own test model; this one tests Cairn from outside, through its public API.

## Model

```text
Person <--EmploymentOf-- Employment --Fills------> Position --BelongsTo--> OrgUnit --PartOf--> OrgUnit
                         Employment --EmployedBy--> LegalEntity            OrgUnit --ManagedBy--> Employment
                         Employment --WorksAt-----> Location               Position --OfJob--> Job
                         Employment <>-HasLeaveRequest--> LeaveRequest --DecidedBy--> Employment
```

| Entity type | Attributes |
| --- | --- |
| `Person` | `givenName` and `familyName` (required), `birthDate`, `fullName` (required, derived from `self.givenName + ' ' + self.familyName`) |
| `LegalEntity` | `name` (required, unique), `country` (required, two capital letters) |
| `Location` | `name` (required, unique), `city`, `country` (required) |
| `Job` | `code` (required, unique), `title` (required) |
| `OrgUnit` | `code` (required, unique), `name` (required) |
| `Position` | `code` (required, unique), `title` (required), `fte` (required, decimal) |
| `Employment` | `employeeNumber` (required, unique, `E-` and three digits), `kind` (required: `permanent` or `fixed-term`), `weeklyHours` (int), `annualSalary` (required, decimal), `currency` (required, three capital letters) |
| `LeaveRequest` | `kind` (required: `vacation`, `sick` or `unpaid`), `firstDay` and `lastDay` (required, dates), `days` (required, decimal, derived from `double(cairn.workingDays(self.firstDay, self.lastDay))`), `status` (required: `requested`, `approved`, `rejected` or `cancelled`) |

| Relationship type | Source | Target | Targets per source | Sources per target | Kind | Inverse name |
| --- | --- | --- | --- | --- | --- | --- |
| `EmploymentOf` | `Employment` | `Person` | 1..1 | 0..1 | association | `employment` |
| `EmployedBy` | `Employment` | `LegalEntity` | 1..1 | 0..* | association | `employments` |
| `WorksAt` | `Employment` | `Location` | 1..1 | 0..* | association | `staff` |
| `Fills` | `Employment` | `Position` | 1..1 | 0..1 | association | `filledBy` |
| `BelongsTo` | `Position` | `OrgUnit` | 1..1 | 0..* | association | `positions` |
| `OfJob` | `Position` | `Job` | 1..1 | 0..* | association | `jobPositions` |
| `PartOf` | `OrgUnit` | `OrgUnit` | 0..1 | 0..* | association | `subunits` |
| `ManagedBy` | `OrgUnit` | `Employment` | 0..1 | 0..* | association | `manages` |
| `HasLeaveRequest` | `Employment` | `LeaveRequest` | 0..* | 1..1 | composition | `requester` |
| `DecidedBy` | `LeaveRequest` | `Employment` | 0..1 | 0..* | association, outlives its target | `decisions` |

| Rule | Version | Type | What it checks | Code |
| --- | --- | --- | --- | --- |
| `no-unit-cycles` | 1 | `PartOf` | Following `PartOf` from a unit never leads back to it. | `cycle` |
| `decided-by-manager` | 1 | `DecidedBy` | The deciding employment is not the requester, and manages the requester's unit, or a unit above it, on the request's first day. | `not-a-manager`, `own-request` |
| `vacation-within-entitlement` | 1 | `LeaveRequest` | An employment's approved vacation days, of requests whose first days are in one calendar year, add up to at most 25. | `over-entitlement` |
| `decided-requests-stay` | 1 | `LeaveRequest` | A leave request that was approved or rejected before the transaction, at any time in the checked version's period, keeps its kind, its first and last days and its days; its status may change only to `cancelled`. | `decided` |

Checks ([ADR-0030](../decisions/0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md)); a request that breaks one is rejected with `CHECK_VIOLATED`:

| Check | Type | Expression | Message |
| --- | --- | --- | --- |
| `leave-dates-in-order` | `LeaveRequest` | `self.lastDay >= self.firstDay` | The last day is before the first day. |

## Organization and people

Units: Brightwater (`BW`), with Engineering (`ENG`) and Sales (`SAL`) below it, and Platform (`PLT`) below Engineering. Locations: Berlin and Milan. Employers: Brightwater GmbH (`DE`) and Brightwater S.r.l. (`IT`). Jobs: `MGR` Manager, `SWE` Software Engineer, `AE` Account Executive.

| Employee | Person | Position | Unit | Location | Employer | Salary | Manages |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E-001 | Ana Berg | P-100, Head of Brightwater (`MGR`) | `BW` | Berlin | GmbH | 150,000 EUR | `BW` |
| E-002 | Ben Ito | P-200, Head of Engineering (`MGR`) | `ENG` | Berlin | GmbH | 130,000 EUR | `ENG` |
| E-003 | Cleo Rossi | P-210, Platform Lead (`MGR`) | `PLT` | Milan | S.r.l. | 110,000 EUR | `PLT` |
| E-004 | Dev Patel | P-211, Software Engineer (`SWE`) | `PLT` | Milan | S.r.l. | 70,000 EUR | |
| E-005 | Eli Novak | P-212, Software Engineer (`SWE`) | `PLT` | Berlin | GmbH | 75,000 EUR | |
| E-006 | Fay Moreau | P-300, Head of Sales (`MGR`) | `SAL` | Berlin | GmbH | 120,000 EUR | `SAL` |
| E-007 | Gus Lind | P-301, Account Executive (`AE`) | `SAL` | Milan | S.r.l. | 60,000 EUR | |

All employments are permanent, 40 hours a week, effective from 1 January 2026. Locations are in `DE` and `IT`. A leave request is effective from the day it is filed, or from its first day if that is earlier; a decision is effective from the day it is recorded, or from the request's first day if that is earlier.

## Stories

Dates are in 2026. Each story has a test whose display name starts with its ID; the tests replay the stories in the order of their recording dates, and the queries HR-9 and HR-10 before HR-4; HR-14 and HR-15 run on stores of their own.

| ID | Story | Exercises |
| --- | --- | --- |
| HR-1 | Recorded 2 January: the employers, locations, jobs, units with their hierarchy and positions exist from 1 January. | Schema documents, payloads, reference data and the unit hierarchy |
| HR-2 | Recorded 2 January: the seven people of the table are hired, and the four managers manage their units, from 1 January. | Generated builders, multiplicities |
| HR-3 | Recorded 10 March: Eli moves to Milan and to Brightwater S.r.l. from 1 July. Effective 30 June Eli works in Berlin; effective 1 July, in Milan. | Future-dated changes |
| HR-4 | Recorded 15 April: correction, Dev's salary was 72,000 from 1 January. Effective 1 February, as known on 1 April it is 70,000; as known now, 72,000. | Corrections, two time axes |
| HR-5 | Recorded 1 March: Dev requests vacation from 16 to 20 March, 5 days. Recorded 2 March: Cleo approves it. Afterwards, ending Cleo's `ManagedBy` of `PLT` from 10 March is rejected, because she would no longer manage Dev's unit on the first day of the vacation she approved (`decided-by-manager/not-a-manager`, about her decision). | Leave requests, approval by the manager, re-checks |
| HR-6 | Recorded 3 March: Eli's vacation request for 20 to 24 April, 5 days, decided by Gus, is rejected, because Gus manages none of Eli's units (`decided-by-manager/not-a-manager`). | Rules on relationships, routing along the hierarchy |
| HR-7 | Recorded 4 March: Dev's approved vacation from 3 to 31 August, 21 days, is rejected, because with HR-5 it makes 26 days in 2026 (`vacation-within-entitlement/over-entitlement`). | Rules that query |
| HR-8 | Recorded 5 March: making Brightwater part of Platform is rejected (`no-unit-cycles/cycle`). | Rules that traverse |
| HR-9 | Effective 31 March: the headcount of each unit with the units below it is `BW` 7, `ENG` 4, `PLT` 3, `SAL` 2. | Queries: traversals, grouping, counts |
| HR-10 | Effective 31 March: Dev's managers, from the nearest up, are Cleo, Ben and Ana. | Queries along the hierarchy |
| HR-11 | Recorded 20 May: Gus was sick on 11 and 12 May, 2 days, approved by Fay. Recorded 20 June: Gus leaves on 30 June; his employment ends with its relationships, and his sick leave and its decision end with it. Effective 1 July, P-301 is the only vacant position; effective 30 June, none is. | End-dating, cascades, vacancies |
| HR-12 | Recorded 1 October: the principal `dpo` erases Gus's personal data under ticket `DSR-2026-0042`. Purging his person alone is refused, because his employment requires one (`TOO_FEW_RELATIONSHIPS`); his person, by ID, and his employment, by its employee number E-007, are purged together, and with them his sick leave and its decision by Fay. Afterwards, the headcount of `SAL` effective 31 March is 1, and P-301 still exists. | Purges, purges by unique key, cascades to compositions, what a purge changes |
| HR-13 | Recorded 1 August: an agent acting as `agent:hr-assistant` hires Hana Kim, E-008, as a Software Engineer in a new position P-213 in `PLT`, in Berlin at Brightwater GmbH for 72,000 EUR from 1 August, through the agent tools, naming the unit, the location, the employer and the job by their unique keys; the commit waits for a person's approval, and after approval Hana exists. | Agent tools, payloads, unique keys, the approval policy |
| HR-14 | A generated company of 40 employees, in a larger organization than the one above (with the units `OPS` and `QA`, Lisbon, Brightwater Lda in `PT` and the job `SPC`), with a year of hires, transfers, pay changes, leave and terminations, one of them a manager's whose decisions stay, from a fixed seed, reads the same from a durable store after reopening as from an in-memory store, and its headcount equals its active employments at every month end. | Durable stores, scale, consistency |
| HR-15 | Recorded 1 March: when Dev requests vacation (HR-5), a notifier that follows the change feed tells Cleo, who manages Dev's unit on the request's first day, that a request waits for her. | The change feed, reading what changed through views |
| HR-16 | Recorded 9 March: moving Dev's approved vacation (HR-5) to 23–27 March is rejected, because a decided request keeps its kind, dates and days (`decided-requests-stay/decided`). | Rules that read the state before the transaction |
| HR-17 | Recorded 1 November: Cleo leaves on 30 November; her employment ends from 1 December with its other relationships, but her decision of Dev's vacation (HR-5) stays, because `DecidedBy` outlives its target. Effective 1 December, Dev's request is decided by Cleo's employment as it last was, whose period ends on 1 December, while reading or listing employments at that time does not find it. | Relationships that outlive their targets |

## Gaps

What the example could not express directly, found while building it. Each is a candidate for a later change.
The first gap it found is closed: a rule ran only when a type that names it was written, so ending Cleo's `ManagedBy` committed; now rules run again when what they read changes ([ADR-0025](../decisions/0025-run-a-rule-again-when-the-data-it-read-changes.md)), as HR-5 shows. So is the second: entities can be named and found by unique keys ([ADR-0026](../decisions/0026-refer-to-entities-by-unique-keys-resolved-at-commit.md)), as the agent of HR-13 does. And the third: a composition cascade ends the other relationships of the targets it ends, so Gus leaving (HR-11) ends the decision of his sick leave with the request (see the `relationships` spec). And the fourth: approvers no longer poll, because a notifier follows the change feed (HR-15, [ADR-0029](../decisions/0029-feed-changes-from-an-index-of-what-each-basis-changed-read-from-a-basis.md)). And the fifth: "the last day is not before the first day" is a check in CEL in the schema document, not a Java rule. And the sixth: a person's `fullName` is derived from the given and family names in the schema document, not composed by every caller. And the seventh: rules and reports traverse the unit hierarchy to the end with `view.traverse(unit, PartOf.TYPE)` and `Traversal`, read rows without casts, name inverse names with `INVERSE` constants, and `vacation-within-entitlement` queries from the requester's employment only, so a pay change to another employment no longer runs it again. And the eighth: a rule reads the state before the transaction, so `decided-requests-stay` keeps a decided request as it was (HR-16). And the ninth: an erasure names Gus's employment by its employee number, without looking up its ID first (HR-12, [ADR-0033](../decisions/0033-name-entities-to-purge-by-unique-keys-that-resolve-over-all-of-effective-time.md)). And the tenth: `DecidedBy` outlives its target, so a manager can leave and their decisions stay, in HR-17 and in the generated company ([ADR-0034](../decisions/0034-let-relationships-of-a-type-outlive-their-targets-as-records-of-past-facts.md)). And the eleventh: a leave's `days` is derived from its dates with `cairn.workingDays`, so they cannot disagree ([ADR-0035](../decisions/0035-give-expressions-functions-of-cairn-s-own-over-a-version-s-values-starting-with-counts-of-days.md)).

| Gap | Where it shows | What Cairn would need |
| --- | --- | --- |
| "At every point in effective time" is the rule author's work | `vacation-within-entitlement` and `no-unit-cycles` collect the instants at which what they read changes, and query or traverse at each | A rule context that lists those instants, or queries over a period |
| Public holidays | A leave's `days` counts Monday to Friday, because public holidays need a calendar that a version's own values do not hold | Calendars as data, or derived values that read other data ([ADR-0035](../decisions/0035-give-expressions-functions-of-cairn-s-own-over-a-version-s-values-starting-with-counts-of-days.md)) |
| Access | Every reader, agents included, reads pay | Field-level access control, which no roadmap names yet |
