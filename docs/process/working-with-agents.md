---
last-reviewed: 2026-09-30
owner: ""
---

# Working with AI agents

This page explains how agent guidance is organized in this repository, how to work with agents day to day, and how to keep the guidance useful.
The template targets Claude Code, and every agent file is stored once. The reasoning is in [ADR-0006](../decisions/0006-target-claude-code-without-duplicated-agent-files.md).

## What agents read

```mermaid
flowchart TD
    claude[CLAUDE.md<br/>@AGENTS.md + Claude notes] -.imports.-> agents[AGENTS.md<br/>canonical, always loaded]
    agents --> area[docs/CLAUDE.md<br/>openspec/CLAUDE.md<br/>loaded when working there]
    agents --> truth[Sources of truth<br/>specs, decisions, glossary,<br/>architecture, principles]
    skills[.claude/skills<br/>loaded on demand] --> truth
    opsx[.claude/commands/opsx<br/>/opsx:* workflows] --> config[openspec/config.yaml<br/>context + rules]
    config --> truth
```

| Layer | File | Loaded | Put here |
| --- | --- | --- | --- |
| Root guide | `AGENTS.md`, imported by `CLAUDE.md` | Every session | Overview, sources of truth, exact commands, workflow, boundaries, repo map |
| Claude notes | `CLAUDE.md` (below the import) | Every Claude Code session | Only what is specific to Claude Code |
| Area rules | `docs/CLAUDE.md`, `openspec/CLAUDE.md` | When Claude works in that folder | Rules that only apply there |
| Skills | `.claude/skills/<name>/SKILL.md` | When the task matches the skill's `description` | Step-by-step procedures: recording a decision, maintaining docs |
| OpenSpec workflows | `.claude/commands/opsx/*.md` (generated) + `openspec/config.yaml` | When you run `/opsx:*` | Project context and per-artifact rules for proposals, specs, designs, tasks |
| Subagents | `.claude/agents/*.md` | When delegated to | Focused reviewers, e.g. `docs-reviewer` |
| Settings | `.claude/settings.json` | By Claude Code | Pre-approved safe commands |

Other tools that read `AGENTS.md` natively (Codex, Copilot, Cursor and others) still get the root guide. Cursor and Copilot also read `.claude/skills/`.

## Day-to-day use

| You want to... | Ask the agent / run |
| --- | --- |
| Think through an idea without writing code | `/opsx:explore` |
| Turn an idea into a proposal with specs, design and tasks | `/opsx:propose "<what and why>"` |
| Record a decision | "Record a decision about X". Triggers the `record-decision` skill |
| Implement an agreed change | `/opsx:apply <change-id>` |
| Adjust a change after feedback | `/opsx:update <change-id>` |
| Check docs are in sync before a PR | "Review docs drift", `docs-reviewer` subagent, `maintain-docs` skill |
| Finish a change | `npm run check`, then `/opsx:archive <change-id>` |

OpenSpec also offers optional workflows (`verify`, `onboard`, `ff` and others). To enable them for the project, add them to the `workflows` list in `scripts/openspec-update.mjs` and run `npm run agents:update`. Ignore OpenSpec's hint to use `openspec config profile`: that changes only your machine's settings, which the script bypasses.

Good habits:

- **Start each task in a fresh session**, pointing at the change id. Long sessions accumulate stale context.
- **Plan before code** for anything non-trivial: proposal first, implementation after review.
- **Give the agent a way to verify its work.** The agent should run `npm run check` and the project tests, not only you.
- **Review agent output like a colleague's PR.** Agents may propose ADRs but never accept them on their own.

## Adding another AI tool

Record the change in a new ADR, since it revisits ADR-0006. Then:

1. Check whether the tool reads `AGENTS.md` natively. Most do, and then nothing else is needed for guidance.
2. If it doesn't, add the thinnest possible adapter that imports or points to `AGENTS.md`, and add it to the table above.
3. Generate OpenSpec workflows for it with `npx openspec init --tools <tool-id>` (ids in `npx openspec init --help`). Delete any `openspec-*` skills it adds to `.claude/skills/`, add the generated folders to the `ignores` in `.markdownlint-cli2.jsonc`, and check that `npm run agents:update` keeps them. Its settings are in `scripts/openspec-update.mjs`.
4. If the tool needs the project skills in its own folder, prefer a tool that reads `.claude/skills/`. Copying skills is what ADR-0006 removed.

## Keeping guidance useful

Research on agent context files is sobering. Long, generic or LLM-generated instruction files often *lower* agent success rates and raise cost. Short, human-written, specific guidance works best.

- **Every line must prevent a real mistake.** Ask: "Would removing this line make the agent do something wrong?" If not, delete it.
- **Grow from failures.** When an agent makes the same mistake twice, add one precise line to `AGENTS.md`, the nearest area `CLAUDE.md`, or a skill, in the same PR as the fix.
- **Commands, not advice.** Exact commands with flags beat "make sure tests pass".
- **Link, don't copy.** Point to specs, ADRs and docs instead of summarizing them.
- **Budget.** Root `AGENTS.md` under about 150 lines, area `CLAUDE.md` files under about 50, and a skill's `SKILL.md` under 500 lines.
- **Checks over prose.** If a rule must always hold, add it to `npm run check` (`scripts/docs.mjs`) or CI and shorten the prose.
- **Review quarterly.** This page, `AGENTS.md` and the `CLAUDE.md` files are living documents. Prune rules that no longer fire.

## Updating generated agent files

The OpenSpec CLI version is pinned in `package.json`. To upgrade:

1. `npm install -D @fission-ai/openspec@<version>`
2. `npm run agents:update`. It runs `openspec update` with the project's settings (commands only, the workflows listed in `scripts/openspec-update.mjs`) to regenerate `.claude/commands/opsx/`. Plain `openspec update` or `openspec init` would follow your machine's global settings and may add `openspec-*` skills, which `npm run check` rejects.
3. Update the version in the global install command in `README.md`.
4. Review the diff, run `npm run check`, and commit. Never hand-edit the generated files.
