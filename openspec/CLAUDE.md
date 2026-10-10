# Rules for openspec/

Claude Code loads this file when working under `openspec/`. The root [AGENTS.md](../AGENTS.md) still applies.
Do not rename it to `AGENTS.md`: OpenSpec 1.x treats `openspec/AGENTS.md` as a legacy file and offers to delete it on `openspec update`.
Format reference: <https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md>.

## Layout

- `specs/<capability>/spec.md`: current behavior. **Read-only by hand.** It changes only when a change is archived.
- `changes/<change-id>/`: `proposal.md`, `specs/<capability>/spec.md` (deltas), optional `design.md`, `tasks.md`, `.openspec.yaml`.
- `changes/archive/YYYY-MM-DD-<change-id>/`: finished changes. Historical record, do not edit.
- `config.yaml`: project context and per-artifact rules that OpenSpec injects into agent instructions.

## Format rules the validator enforces

- Each requirement is `### Requirement: <name>`. Its body contains SHALL or MUST, and it has at least one `#### Scenario:` (exactly four `#`).
- Scenarios are bullet lines `- **WHEN** ...` / `- **THEN** ...` (optionally `- **GIVEN**`, `- **AND**`). EARS-style wording (`WHEN <trigger> THE SYSTEM SHALL <response>`) keeps them testable.
- Deltas use `## ADDED Requirements`, `## MODIFIED Requirements`, `## REMOVED Requirements` (with `**Reason**` and `**Migration**`), and `## RENAMED Requirements` (`- FROM:` / `- TO:`).
- A `MODIFIED` requirement must repeat the **whole** requirement block, with the header copied exactly. Anything left out is lost on archive.
- A change without behavior change sets `skip_specs: true` in `.openspec.yaml`. Do not invent requirements to satisfy validation.

## Conventions

- Change ids are kebab-case and start with a verb: `add-`, `change-`, `remove-`, `fix-`, `refactor-`.
- Capability names are kebab-case nouns (`invoicing`, `identity/user-auth`). Reuse existing names: check `npx openspec list --specs` first.
- Refer to ADRs by ID ("ADR-0002"), not by relative link, because archiving moves the folder.
- Validate with `npx openspec validate <change-id> --strict` before asking for review.
