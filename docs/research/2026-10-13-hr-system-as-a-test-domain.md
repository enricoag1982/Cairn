---
status: concluded
date: 2026-10-13
authors: [enricoag1982, Claude Code]
related: [ADR-0008, ADR-0010, ADR-0017, ADR-0018, ADR-0023, ADR-0024]
---

# Research: a basic HR system as an end-to-end test of Cairn

## Question

What are the basic concepts of an HR system, and how would they be modeled and run on Cairn?
The goal is to test the whole platform on a realistic domain: define the model, load test data, and run the usual HR changes and questions, also the way agents would through the agent tools. The answer feeds the change `add-hr-example`, and the gaps it finds feed later changes.

## Findings

### What HR systems keep

- **Person and employment are separate.** The person holds personal data; the employment (a role of the person at an employer) holds what HR changes over time: position, organization, location, pay. SAP SuccessFactors Employee Central separates person objects from employment objects, and keeps job information effective-dated: a new record starts on its effective date and the previous one ends the day before ([SAP Learning](https://learning.sap.com/courses/sap-successfactors-employee-central-core-administration/describing-the-standard-hris-elements-in-sap-successfactors-employee-central), [SAP Community](https://groups.community.sap.com:443/t5/human-capital-management-blogs-by-sap/employee-central-job-information-entity-configuration-considerations/ba-p/13566976)).
- **Organization units form a management hierarchy**, Workday's supervisory organizations; positions belong to a unit, and workers are hired into positions ([Workday glossary, Santa Clara University](https://www.scu.edu/media/offices/human-resources/documents/workday/Wokday_-Glossary-of-Terms.pdf)).
- **Jobs and positions differ.** A job is a profile (title, family); a position is a seat in a unit, which one employment fills at a time and which can be vacant.
- **Locations and legal employers** are reference data that employments point to.
- **Approval processes** move a request, such as a hire, a pay change or leave, through approval steps routed along the management or organization hierarchy ([Workday admin guide](https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/business-process-framework-concepts/yzb1658777642966.html), [Grand Valley State University](https://gvsu.edu/workday/what-is-a-business-process-20.htm)).
- **Sensitive data** such as pay is protected by permissions per field ([SAP Learning, position management](https://learning.sap.com/courses/sap-successfactors-employee-central-position-management-academy-jp/reviewing-the-position-information-of-employees_e0d7113c-36cd-469e-a639-b14ae389919f)), and personal data of former employees must be erased when its retention ends (GDPR).

### How it maps to Cairn

| HR concept | Cairn | What it exercises |
| --- | --- | --- |
| Person, employer, location, job | Entity types | Schema documents, uniqueness, allowed values |
| Employment, with pay and hours | Entity type, effective-dated | Hires, transfers, pay changes, terminations on effective time; future-dated and retroactive changes, corrections |
| Organization unit hierarchy | `PartOf` from unit to parent, at most one parent | Traversals, a Java rule against cycles |
| Manager of a unit | `ManagedBy` from unit to employment | Navigation, routing approvals |
| Position in a unit, of a job, filled by an employment | `BelongsTo`, `OfJob`, `Fills`, with multiplicities | A position filled by one employment at a time; vacancies |
| Leave request | Entity type with a status, a composition of the employment | Allowed values, compositions that go with their source at a purge |
| Approval | `DecidedBy` from request to the deciding employment, and Java rules | The approver must manage the requester's unit or one above it; approved vacation within the yearly entitlement, through a query in a rule |
| Headcount, vacancies, org charts | Queries | Grouping by unit, traversals up and down the hierarchy, two time axes |
| Erasure of a former employee | Purge | Cascades to compositions, refusals for required relationships |

### Gaps expected

- **State transitions.** A rule sees the version a transaction writes, and the state that transaction produces, but not the version before it, so "a rejected request never changes again" cannot be written as a rule. Candidate: give rules the previous version.
- **Cross-attribute constraints**, such as "the last day is not before the first day", need a Java rule today. Candidate: CEL expressions (roadmap).
- **Notifications and workflow.** Telling an approver that a request waits needs a change feed (roadmap); Cairn keeps the state, not the process.
- **Field-level access control** for pay: Cairn has none yet.
- **Derived attributes** such as tenure or a full name: roadmap.

## Options and trade-offs

- **Where it lives.** A Gradle subproject, `cairn-example-hr`, built only on Cairn's public API, like an application would be: its own schema document, rules, generated types and tests. Keeping it in the repository runs it on every build, so the platform cannot drift away from it; a separate repository would hide breakage until someone runs it.
- **How it is described.** A page in `docs/reference/` with the model and numbered stories with expected outcomes, as the reference domain has, so tests and people check the same thing. The reference domain stays the engine's own test model; the HR domain tests the platform from outside.
- **Test data.** Handwritten stories for the cases that matter, plus a generated company of a few dozen employees with a year of history from a fixed seed, so results are the same on every run.
- **Agents.** Agent paths are scripted through the agent tools, with payloads and queries as JSON, as the agent task suite does; no language model runs in tests.

## Conclusion

An HR system fits Cairn's model well: employments on effective time, a hierarchy of units, positions with multiplicities, approvals as data plus Java rules, reporting as queries, and erasure as purges. The test should show this end to end, through schema documents, payloads, generated types, rules, queries, durable stores and purges, and record each gap it hits.

Next step: open the change `add-hr-example`, with the model and stories in `docs/reference/hr-domain.md` and the subproject `cairn-example-hr`. The gaps above become candidates for the roadmap's later changes, in the order the example shows they matter.

## Sources

- HR Open Standards: <https://www.hropenstandards.org/>
- ISO 30414, human capital reporting: <https://www.iso.org/standard/69338.html>
- SAP Learning, standard HRIS elements in Employee Central: <https://learning.sap.com/courses/sap-successfactors-employee-central-core-administration/describing-the-standard-hris-elements-in-sap-successfactors-employee-central>
- SAP Community, job information entity configuration: <https://groups.community.sap.com:443/t5/human-capital-management-blogs-by-sap/employee-central-job-information-entity-configuration-considerations/ba-p/13566976>
- Workday admin guide, business process framework concepts: <https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/business-process-framework-concepts/yzb1658777642966.html>
- Workday glossary of terms, Santa Clara University: <https://www.scu.edu/media/offices/human-resources/documents/workday/Wokday_-Glossary-of-Terms.pdf>
- What is a business process, Grand Valley State University: <https://gvsu.edu/workday/what-is-a-business-process-20.htm>
