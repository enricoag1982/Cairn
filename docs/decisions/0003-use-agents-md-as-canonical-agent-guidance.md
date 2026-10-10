---
status: superseded by ADR-0006
date: 2026-09-30
decision-makers: [project maintainers]
consulted: []
informed: []
---

# Use AGENTS.md as the canonical agent guidance, with thin tool-specific adapters

## Context and Problem Statement

Several AI coding tools will work in this repository (Claude Code, Codex, GitHub Copilot, Cursor, Gemini CLI and others). Each has its own instruction file and skill folder.
Copying the same guidance into each tool's file guarantees drift. Long or generated context files also make agents worse: they cost tokens and dilute the instructions that matter.
We need one source of agent guidance that every tool reads, and a rule for keeping it small.

## Decision Drivers

- One place to edit guidance, with no copy-paste between tool files
- Works with the tools in use today, degrades gracefully for others
- Guidance stays short and specific, and grows only when agents make real mistakes
- Hard rules are enforced by checks, not only by prose

## Considered Options

- `AGENTS.md` canonical, plus thin tool adapters (`CLAUDE.md` importing it, Gemini settings)
- `CLAUDE.md` canonical, with other tools pointed at it
- Separate hand-maintained files per tool
- Symlink `CLAUDE.md` to `AGENTS.md`

## Decision Outcome

Chosen option: "`AGENTS.md` canonical, plus thin tool adapters", because AGENTS.md is the open standard (stewarded by the Agentic AI Foundation under the Linux Foundation). Codex, Copilot, Cursor, Devin/Windsurf, Zed, Junie and many others read it natively, and the remaining tools can be pointed at it cheaply.

How it is wired:

| Tool | Reads | Adapter in this repo |
| --- | --- | --- |
| Codex, Copilot, Cursor, Devin/Windsurf, others | `AGENTS.md` (nearest file wins) | none needed |
| Claude Code | `CLAUDE.md`. It reads `AGENTS.md` only when no `CLAUDE.md` exists | `CLAUDE.md` = `@AGENTS.md` + Claude-only notes; the same one-line `CLAUDE.md` next to each nested `AGENTS.md` |
| Gemini CLI | `GEMINI.md` by default | `.gemini/settings.json` sets `context.fileName` to `AGENTS.md` |

Skills follow the [Agent Skills](https://agentskills.io/) open standard (`<name>/SKILL.md` with `name` and `description` frontmatter).
Project skills are written in `.agents/skills/`, which Codex, Gemini CLI, Cursor and Copilot read.
`npm run skills:sync` copies them to `.claude/skills/` for Claude Code, and `npm run docs:check` fails if the copies drift.
OpenSpec's own skills are generated per tool by `openspec init`/`update`.

Rules for content:

- The root `AGENTS.md` stays under about 150 lines: overview, sources of truth, exact commands, workflow, boundaries (always / ask first / never), and a map.
- Detail goes into nested `AGENTS.md` files (`docs/`, `openspec/`), skills or linked docs.
- Add a line only when an agent made a real mistake that it would prevent. Do not generate these files with an LLM.
- Rules that must always hold become checks (`npm run check` in CI) instead of more prose.

### Consequences

- Good, because there is a single edit point, and most tools need no adapter.
- Good, because nested files and skills give agents detail only when they work in that area.
- Bad, because Claude Code needs a one-line `CLAUDE.md` next to each nested `AGENTS.md`. Without it, the root `CLAUDE.md` hides nested `AGENTS.md` files from Claude.
- Bad, because skills exist in two folders. This is mitigated by the sync script and CI check.
- Neutral, because tool support changes quickly. Re-check the adapter table when adding a tool.

### Confirmation

Reviews check that tool-specific files only import or point to `AGENTS.md`. `npm run docs:check` verifies the skill copies and frontmatter.

## Pros and Cons of the Options

### CLAUDE.md canonical

- Good, because Claude Code needs no adapter.
- Bad, because most other tools do not read it.

### Separate files per tool

- Good, because each file can be tuned per tool.
- Bad, because it guarantees drift, and every change costs N edits.

### Symlink CLAUDE.md to AGENTS.md

- Good, because there is literally one file.
- Bad, because symlinks break on Windows checkouts without Developer Mode, and there is no place for Claude-only notes.

## More Information

- Superseded by [ADR-0006](0006-target-claude-code-without-duplicated-agent-files.md): Claude Code only, each agent file stored once.
- AGENTS.md: <https://agents.md/>
- Claude Code memory and AGENTS.md support: <https://code.claude.com/docs/en/memory>
- Agent Skills specification: <https://agentskills.io/>
- Research and sources: [2026-09-30 standards for agent-maintained project knowledge](../research/2026-09-30-standards-for-agent-maintained-project-knowledge.md)
- How the setup is used day to day: [working with agents](../process/working-with-agents.md)
