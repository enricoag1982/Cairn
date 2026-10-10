---
name: record-decision
description: Record, update or supersede an Architecture Decision Record (ADR) in docs/decisions using the MADR 4 template. Use when a choice is costly to reverse, affects several capabilities, picks a technology, library or pattern, or settles something argued about more than once; also when the user says "ADR", "decision record" or "let's decide".
license: MIT
metadata:
  source: project-template
---

# Record a decision

ADRs live in `docs/decisions/` and use MADR 4. Rules: `docs/decisions/0001-record-architecture-decisions-with-madr.md`.

## Is an ADR needed?

Write one if at least one of these holds. Otherwise keep the reasoning in the change's `design.md`.

- It is costly or slow to reverse (language, framework, persistence, public API style, module boundaries)
- It constrains more than one capability or team
- There were real alternatives with trade-offs
- It has been discussed before without a written outcome

## New decision

1. Read `docs/decisions/README.md`. Look for an existing ADR on the topic. If one exists, supersede it (below) instead of duplicating it.
2. `npm run adr:new -- "<problem and chosen solution, short>"`. This creates `docs/decisions/NNNN-<slug>.md` with `status: proposed` and updates the log.
3. Fill in the sections: context and problem, decision drivers, at least two considered options with honest pros and cons, and the outcome with consequences (good *and* bad). Delete optional sections you do not use, and delete the template comment.
4. Link sources: the OpenSpec change (by id), issues, research notes in `docs/research/`.
5. Leave `status: proposed` until a human agrees. **Never mark your own proposal `accepted`** unless the user explicitly decided it.
6. Reference the ADR by ID from the change's `design.md` and `proposal.md`.

## Accepting or rejecting

Set `status: accepted` or `status: rejected`, update `date`, fill in `decision-makers`, run `npm run docs:index`.
If accepted, update what the decision affects: `docs/architecture/README.md` (constraints, strategy), `AGENTS.md` commands or boundaries, `openspec/config.yaml` context.

## Superseding (changing a decided question)

1. Create a new ADR as above. In "Context and Problem Statement", say what changed since the old decision.
2. In the **old** ADR, change only the frontmatter to `status: superseded by ADR-NNNN` and add one line under "More Information" linking the new ADR. Do not edit its reasoning.
3. `npm run docs:index`.

## Finish

`npm run docs:lint:fix && npm run docs:check`, both must pass.
