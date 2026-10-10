---
status: accepted
date: 2026-09-30
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Target Claude Code, keep AGENTS.md canonical, and store each agent file once

## Context and Problem Statement

[ADR-0003](0003-use-agents-md-as-canonical-agent-guidance.md) wired the template for many AI tools at once. The result was that every skill existed twice: in `.agents/skills/` (for Codex and Gemini CLI) and in `.claude/skills/` (for Claude Code), kept in step by a sync script. There was also a `.gemini/settings.json` adapter.
On top of that, OpenSpec's default "both" delivery wrote each of its six workflows twice for Claude Code, as an `openspec-*` skill and as an `/opsx:*` command. Claude listed both.
That made 22 skill and command files for 8 distinct skills and workflows. It was confusing to browse and easy to edit in the wrong place.

A second problem showed up while simplifying. OpenSpec 1.x treats `openspec/AGENTS.md` as a legacy file from its 0.x format, and offers to delete it on `openspec update`, so the nested guide for `openspec/` could not keep that name.

## Decision Drivers

- Each agent file is stored once, with no copies to keep in sync
- The tool actually used, Claude Code, works fully, with each OpenSpec workflow listed once
- Keep the good parts of ADR-0003: `AGENTS.md` canonical, short guidance, and checks over prose
- Generated files stay reproducible, whatever a contributor's global OpenSpec settings are

## Considered Options

- Claude Code only: `.claude/` holds skills and `/opsx:*` commands; `AGENTS.md` stays canonical
- Claude Code plus Codex and Gemini CLI: keep `.agents/skills/` and the sync script, drop only the duplicate OpenSpec skills
- Keep the ADR-0003 setup unchanged

## Decision Outcome

Chosen option: "Claude Code only", because it removes every duplicate and the sync machinery. Other tools lose little: Cursor and Copilot also read `.claude/skills/`, and Codex, Copilot and Cursor read `AGENTS.md` directly.

What changes compared with ADR-0003 (and the note in ADR-0002 about generated integrations):

| Item | Before | Now |
| --- | --- | --- |
| Project skills | `.agents/skills/` copied to `.claude/skills/` by `npm run skills:sync` | `.claude/skills/` only; the sync script is removed |
| OpenSpec workflows | `openspec-*` skills and `/opsx:*` commands, in `.claude/` and `.agents/` | `/opsx:*` commands in `.claude/commands/opsx/` only |
| Regenerating them | `openspec update` (uses each machine's global settings) | `npm run agents:update`, which runs `openspec update` with project settings: delivery `commands`, the six core workflows |
| Gemini CLI | `.gemini/settings.json` pointing to `AGENTS.md` | removed |
| Area guides | `docs/AGENTS.md` and `openspec/AGENTS.md`, each with a one-line `CLAUDE.md` import | `docs/CLAUDE.md` and `openspec/CLAUDE.md` holding the rules directly |

Unchanged from ADR-0003:

- The root `AGENTS.md` is the canonical guide. `CLAUDE.md` imports it and adds Claude-only notes.
- Guidance stays short, grows from real mistakes, and rules that must always hold become checks.

### Consequences

- Good, because skill and command files go from 22 to 8, each stored once, and every OpenSpec workflow appears once in Claude Code.
- Good, because `npm run docs:check` fails if `openspec-*` skills reappear, for example after a plain `openspec init`, and names the fix.
- Good, because the nested `openspec/` guide no longer collides with OpenSpec's legacy-file cleanup.
- Bad, because Codex and Gemini CLI no longer get the project skills or OpenSpec workflows, and Gemini CLI no longer reads `AGENTS.md` without its own settings.
- Bad, because tools other than Claude Code do not load the area rules in `docs/CLAUDE.md` and `openspec/CLAUDE.md` automatically. The root `AGENTS.md` points to them.

### Confirmation

`npm run docs:check` validates `.claude/skills/*/SKILL.md` frontmatter and rejects `openspec-*` skill folders. `npm run agents:update` on a clean checkout leaves the working tree unchanged.

## Pros and Cons of the Options

### Claude Code plus Codex and Gemini CLI

- Good, because Codex and Gemini CLI would keep the skills and workflows.
- Bad, because skills stay duplicated, with a sync script and CI check to maintain, for tools nobody on the project uses yet.

### Keep the ADR-0003 setup

- Good, because it needs no work.
- Bad, because of 22 skill and command files for 8 skills and workflows, and each OpenSpec workflow listed twice in Claude Code.

## More Information

- To add another tool later, see [working with AI agents](../process/working-with-agents.md#adding-another-ai-tool) and record the change in a new ADR.
- Supersedes [ADR-0003](0003-use-agents-md-as-canonical-agent-guidance.md).
