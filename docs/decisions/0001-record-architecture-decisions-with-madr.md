---
status: accepted
date: 2026-09-30
decision-makers: [project maintainers]
consulted: []
informed: []
---

# Record architecture decisions as MADR files in the repository

## Context and Problem Statement

This project will make many decisions, and many of them will be revisited while requirements change.
Decisions made in chats, meetings, issue threads or agent sessions get lost. People and AI agents then re-argue settled questions, or silently undo a decision because they cannot see why it was made.
We need a durable, reviewable, searchable record of *why* the system looks the way it does.

## Decision Drivers

- Decisions must be readable by humans and by AI agents from the repository alone
- Recording a decision must be cheap enough that people actually do it
- The history of a decision (including reversals) must stay visible
- Must work with plain Markdown, GitHub rendering and Obsidian

## Considered Options

- MADR 4 files in `docs/decisions/`
- Michael Nygard's original ADR format
- Design documents or RFCs only
- Wiki pages or issue threads

## Decision Outcome

Chosen option: "MADR 4 files in `docs/decisions/`", because it is a maintained, widely used template with structured options and consequences. Its YAML frontmatter can be checked by tooling and queried in Obsidian.

Rules:

- One decision per file, named `NNNN-title-with-dashes.md`, numbered sequentially. Create one with `npm run adr:new -- "Title"`.
- `status` is one of `proposed`, `accepted`, `rejected`, `deprecated`, `superseded by ADR-NNNN`.
- An accepted ADR is not rewritten. To change a decision, write a new ADR and mark the old one `superseded by ADR-NNNN`. Typos and links may be fixed.
- A `proposed` ADR is an open question. The decision log shows every open decision at a glance.
- Write an ADR when a decision is costly to reverse, affects several capabilities, or has been argued about more than once.
- The decision log in [README.md](README.md) is generated (`npm run docs:index`) and checked in CI.

### Consequences

- Good, because the reasoning survives turnover of people, tools and agent sessions.
- Good, because agents can be told to read the decision log before proposing changes.
- Good, because superseding keeps a full audit trail of reversals.
- Bad, because writing ADRs takes discipline. The PR template and definition of done ask for them.
- Neutral, because OpenSpec `design.md` files also contain decisions. Small, local ones stay there; significant ones get an ADR linked from the design.

### Confirmation

`npm run docs:check` fails on malformed file names, missing or invalid status or date, dangling `superseded by` references and an out-of-date decision log.

## More Information

- MADR: <https://adr.github.io/madr/>
- Michael Nygard, "Documenting Architecture Decisions" (2011): <https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions>
- Background research: [2026-09-30 standards for agent-maintained project knowledge](../research/2026-09-30-standards-for-agent-maintained-project-knowledge.md)
