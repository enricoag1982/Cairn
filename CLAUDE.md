@AGENTS.md

## Claude Code

- OpenSpec workflows are the `/opsx:*` commands in `.claude/commands/opsx/`. They are generated; refresh them with `npm run agents:update`.
- Project skills live in `.claude/skills/`: `record-decision`, `maintain-docs`.
- Before finishing a change, delegate a drift review to the `docs-reviewer` subagent.
- Personal, uncommitted notes go in `CLAUDE.local.md`.
