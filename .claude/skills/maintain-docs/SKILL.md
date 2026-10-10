---
name: maintain-docs
description: Keep project documentation in sync with a change. Use after implementing or archiving an OpenSpec change, after accepting an ADR, when renaming concepts, when docs look stale or contradict code or specs, or when the user asks to update, review or clean up docs, the glossary or architecture pages.
license: MIT
metadata:
  source: project-template
---

# Maintain documentation

Rules: `docs/process/documentation-lifecycle.md`. Each fact has one home and everything else links to it.

## 1. Find what the change touched

- `git diff --stat origin/main...HEAD`, or the files of the current OpenSpec change
- Specs affected: `npx openspec show <change-id> --deltas-only`
- ADRs created or changed in `docs/decisions/`

## 2. Walk the checklist

| If the change... | Update |
| --- | --- |
| introduces or renames a domain concept | `docs/glossary.md` (alphabetical, 1-2 sentences, link the spec) |
| adds or changes a component, integration, data store or deployment | `docs/architecture/README.md` (building blocks, context diagram, deployment) |
| changes how to set up, build, run, release or debug | the relevant file in `docs/guides/` (create from `docs/templates/guide.md`) |
| changes configuration, CLI, API surface or error codes | `docs/reference/` |
| changes how the team or agents work | `docs/process/`, `AGENTS.md`, an area `CLAUDE.md` (`docs/`, `openspec/`), or a skill |
| accepts, rejects or supersedes an ADR | ADR status, `npm run docs:index`, and anything the decision affects |
| finishes an OpenSpec change | archive it: `npx openspec archive <change-id> -y` |

For each living document you verified or edited, set `last-reviewed` to today.

## 3. Remove drift, don't add it

- Restated facts: replace the copy with a link to the canonical source.
- Contradictions between docs and specs: the archived spec wins for behavior, the accepted ADR wins for reasoning. Fix the doc, or raise the conflict with the user if the spec looks wrong.
- Stale warnings from `npm run docs:check` (`last-reviewed` older than 180 days): verify the content and bump the date, or flag what you could not verify.

## 4. Verify

`npm run docs:lint:fix && npm run check`. Report which documents you changed and anything you could not reconcile.
