---
last-reviewed: 2026-10-13
owner: ""
---

# HR domain

A basic HR system for a fictional company, Brightwater, built on Cairn as an application would be: the end-to-end test of the platform.
Why it looks like this is in the [research note](../research/2026-10-13-hr-system-as-a-test-domain.md). The code lives in the subproject `cairn-example-hr`, with its schema document in `cairn-example-hr/src/main/cairn/hr-model.cairn.yaml`.
The [reference domain](reference-domain.md) stays the engine's own test model; this one tests Cairn from outside, through its public API.

## Model

```text
Person <-EmploymentOf-- Employment --Fills--> Position --BelongsTo--> OrgUnit --PartOf--> OrgUnit
                         |   |   |                |                    |
                         |   |   +--EmployedBy--> LegalEntity          +--ManagedBy--> Employment
                         |   +--WorksAt--> Location   +--OfJob--> Job
                         +<>-HasLeaveRequest--> LeaveRequest --DecidedBy--> Employment
```

| Entity type | Attributes |
| --- | --- |
| `Person` | `givenName` and `familyName` (required), `birthDate` |
| `LegalEntity` | `name` (required, unique), `country` (required, two capital letters) |
| `Location` | `name` (required, unique), `city`, `country` (required) |
| `Job` | `code` (required, unique), `title` (required) |
| `OrgUnit` | `code` (required, unique), `name` (required) |
| `Position` | `code` (required, unique), `title` (required), `fte` (required, decimal) |
| `Employment` | `employeeNumber` (required, unique, `E-` and three digits), `kind` (required: `permanent` or `fixed-term`), `weeklyHours`, `annualSalary` (required, decimal), `currency` (required, three capital letters) |
| `LeaveRequest` | `kind` (required: `vacation`, `sick` or `unpaid`), `firstDay` and `lastDay` (required, dates), `days` (required, decimal), `status` (required: `requested`, `approved`, `rejected` or `cancelled`) |

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
| `DecidedBy` | `LeaveRequest` | `Employment` | 0..1 | 0..* | association | `decisions` |

| Rule | Version | Type | What it checks | Code |
| --- | --- | --- | --- | --- |
| `no-unit-cycles` | 1 | `PartOf` | Following `PartOf` from a unit never leads back to it. | `cycle` |
| `decided-by-manager` | 1 | `DecidedBy` | The deciding employment manages the requester's unit, or a unit above it, on the request's first day. | `not-a-manager` |
| `vacation-within-entitlement` | 1 | `LeaveRequest` | An employment's approved vacation days in a calendar year add up to at most 25. | `over-entitlement` |
| `leave-dates-in-order` | 1 | `LeaveRequest` | The last day is not before the first day. | `last-before-first` |

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

All employments are permanent, 40 hours a week, effective from 1 January 2026.

## Stories

Dates are in 2026. Each story has a test whose display name starts with its ID.

| ID | Story | Exercises |
| --- | --- | --- |
| HR-1 | Recorded 2 January: the employers, locations, jobs, units with their hierarchy and positions exist from 1 January. | Schema documents, payloads, compositions of the model |
| HR-2 | Recorded 2 January: the seven people of the table are hired, and the four managers manage their units, from 1 January. | Generated builders, multiplicities |
| HR-3 | Recorded 10 March: Eli moves to Milan and to Brightwater S.r.l. from 1 July. Effective 30 June Eli works in Berlin; effective 1 July, in Milan. | Future-dated changes |
| HR-4 | Recorded 15 April: correction, Dev's salary was 72,000 from 1 January. Effective 1 February, as known on 1 April it is 70,000; as known now, 72,000. | Corrections, two time axes |
| HR-5 | Recorded 1 March: Dev requests vacation from 16 to 20 March, 5 days. Recorded 2 March: Cleo approves it. | Leave requests, approval by the manager |
| HR-6 | Recorded 3 March: Eli's vacation request decided by Gus is rejected, because Gus manages none of Eli's units (`decided-by-manager/not-a-manager`). | Rules on relationships, routing along the hierarchy |
| HR-7 | Recorded 4 March: Dev's approved vacation of 22 more days in August is rejected, because with HR-5 it makes 27 days in 2026 (`vacation-within-entitlement/over-entitlement`). | Rules that query |
| HR-8 | Recorded 5 March: making Brightwater part of Platform is rejected (`no-unit-cycles/cycle`). | Rules that traverse |
| HR-9 | Effective 31 March: the headcount of each unit with the units below it is `BW` 7, `ENG` 4, `PLT` 3, `SAL` 2. | Queries: traversals, grouping, counts |
| HR-10 | Effective 31 March: Dev's managers, from the nearest up, are Cleo, Ben and Ana. | Queries along the hierarchy |
| HR-11 | Recorded 20 May: Gus was sick on 11 and 12 May, 2 days, approved by Fay. Recorded 20 June: Gus leaves on 30 June. Effective 1 July, P-301 is the only vacant position; effective 30 June, none is. | End-dating, vacancies |
| HR-12 | Recorded 1 October: the principal `dpo` erases Gus's personal data under ticket `DSR-2026-0042`: his person and employment are purged, and with them his sick leave and its decision by Fay. Afterwards, the headcount of `SAL` effective 31 March is 1, and P-301 still exists. | Purges, cascades to compositions, what a purge changes |
| HR-13 | Recorded 1 August: an agent acting as `agent:hr-assistant` hires Hana Kim as a Software Engineer in a new position P-213 in `PLT`, through the agent tools; the commit waits for a person's approval, and after approval Hana exists. | Agent tools, payloads, the approval policy |
| HR-14 | A generated company of 40 employees with a year of hires, transfers, pay changes, leave and terminations, from a fixed seed, reads the same from a durable store after reopening as from an in-memory store, and its headcount equals its active employments at every month end. | Durable stores, scale, consistency |

## Gaps

The example records here what Cairn could not express, as it finds it. Expected, from the research note: state transitions of leave requests, notifications to approvers, field-level access to pay, and derived attributes such as tenure.
