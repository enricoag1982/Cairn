---
status: draft
date: 2026-10-13
authors: [enricoag1982, Claude Code]
related: [ADR-0008, ADR-0010, ADR-0017, ADR-0018]
---

# Research: a basic HR system as an end-to-end test of Cairn

> [!NOTE]
> **To do, later.** This note is a placeholder. Start it once the platform is well implemented: schema documents, entities, relationships, rules, JSON payloads, queries and agent tools. See the roadmap in the [AI-native research note](2026-10-13-ai-native-schema-and-rules.md#conclusion).

## Question

What are the basic concepts of an HR system, and how would they be modeled and run on Cairn?
The goal is to test the whole platform on a realistic domain: define the model, load test data, and run the usual HR changes and questions, ideally with agents doing the work through the agent tools.

## Findings

To investigate:

- **Basic concepts:** employees and their employments (a role of a person), positions and jobs, organization units and their hierarchy, locations, managers (reports to), cost centers, compensation, absences, and approval processes (requests, approvers, states, delegation).
- **How each maps to Cairn:** entity types, traits, relationship types, multiplicities, compositions, rules and constraints. Which approval-process needs fit data plus rules, and which need something Cairn does not have, such as state transitions, notifications or a scheduler for "now effective" events.
- **What it exercises:** hires, transfers and terminations on effective time; future-dated and retroactive changes; corrections; the organization hierarchy as a recursive traversal; headcount by organization unit at a date; uniqueness of employee numbers; erasure of former employees' data (purge, ADR-0008).
- **Gaps it may reveal:** access control to sensitive attributes such as salary, derived attributes such as tenure, workflow, reporting.
- **Test data:** a fictional company with a few locations, organization units and a few dozen employees, with a year of history that includes corrections and future-dated changes.
- **Prior art to read:** HR Open Standards, the business objects of Workday and SAP SuccessFactors Employee Central (both effective-dated), and ISO 30414 for human capital reporting.
- **Where it lives:** a separate example (folder or repository) built on Cairn's public API, not part of the engine; the reference domain stays the engine's own test model.

## Options and trade-offs

To be written.

## Conclusion

To be written.

## Sources

- HR Open Standards: <https://www.hropenstandards.org/>
- ISO 30414, human capital reporting: <https://www.iso.org/standard/69338.html>
