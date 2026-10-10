---
last-reviewed: 2026-09-30
owner: ""
---

# Glossary

The project's shared vocabulary (the *ubiquitous language* in Domain-Driven Design terms).
Use these terms, spelled exactly like this, in specs, code, docs and conversations with agents.
When a new term shows up in a spec or a discussion, add it here in the same change.

<!-- Keep entries alphabetical. One or two sentences each. Link to the spec or ADR that defines the concept. -->

## Process terms

These come with the template. Add domain terms in their own section below.

| Term | Meaning |
| --- | --- |
| ADR | Architecture Decision Record: one file in `docs/decisions/` that records one significant decision, its context and its consequences. |
| Capability | A cohesive area of behavior with its own spec in `openspec/specs/<capability>/spec.md`. |
| Change | A proposed modification in `openspec/changes/<change-id>/`: proposal, delta specs, optional design, tasks. Archived when done. |
| Delta spec | The part of a change that says which requirements are ADDED, MODIFIED, REMOVED or RENAMED. Merged into the main specs on archive. |
| Living document | A document describing the current state. It is edited in place and carries `last-reviewed`. |
| Requirement | A normative statement (SHALL/MUST) inside a spec, with at least one scenario. |
| Research note | Non-normative exploration in `docs/research/`. Its conclusions move into an ADR or a spec. |
| Scenario | A concrete WHEN/THEN example that makes a requirement testable. |
| Supersede | Replace an accepted ADR with a newer one. The old one stays, with status `superseded by ADR-NNNN`. |

## Domain terms

| Term | Meaning |
| --- | --- |
| *(none yet)* | |
