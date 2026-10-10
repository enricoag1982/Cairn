# OpenSpec Project Structure

A reusable repository structure for complex, long-lived projects with many decisions and fast-changing documentation, built to be worked on by humans and AI coding agents together.

It combines:

- [OpenSpec](https://github.com/Fission-AI/OpenSpec) for spec-driven development
- [MADR](https://adr.github.io/madr/) decision records
- a trimmed [arc42](https://arc42.org/) architecture page
- a canonical [AGENTS.md](https://agents.md/), set up for [Claude Code](https://code.claude.com/) with project skills and OpenSpec slash commands
- plain Markdown that you can also browse as an [Obsidian](https://obsidian.md/) vault

A small `npm run check` keeps it all honest in CI.
The implementation language is deliberately not chosen yet ([ADR-0005](docs/decisions/0005-choose-primary-implementation-language.md)).

## Why this structure

Complex projects lose knowledge in three ways: people forget **why** something was decided, nobody knows **what** the system is currently supposed to do, and docs silently **rot**. AI agents make all three worse, because they only know what is in the repository.
Every kind of knowledge therefore has exactly one home, a lifecycle and a check:

| Knowledge | Home | Lifecycle | Checked by |
| --- | --- | --- | --- |
| What the system must do | `openspec/specs/` | Changed only through reviewed OpenSpec changes | `openspec validate --strict` |
| What we are changing | `openspec/changes/` | Proposal, then implementation, then archive | `openspec validate --strict` |
| Why it is built this way | `docs/decisions/` (ADRs) | Append-only; superseded, never rewritten | `docs:check` (status, dates, log) |
| How it is built now | `docs/architecture/`, `guides/`, `reference/` | Living; `last-reviewed` date | `docs:check` (freshness, links) |
| Shared vocabulary and principles | `docs/glossary.md`, `docs/principles.md` | Living | `docs:check` |
| Explorations | `docs/research/` | Dated, non-normative | `docs:check` (links) |
| How agents should behave | `AGENTS.md`, `CLAUDE.md` files, skills | Grows from real mistakes | `docs:check` (skill format, no duplicate skills) |

## Layout

```text
.
├── AGENTS.md                  # canonical guide for AI agents (and a good summary for humans)
├── CLAUDE.md                  # Claude Code adapter: imports AGENTS.md
├── openspec/
│   ├── config.yaml            # project context + rules injected into OpenSpec workflows
│   ├── CLAUDE.md              # spec format rules
│   ├── specs/                 # current behavior, one folder per capability
│   └── changes/               # in-flight changes; archive/ holds finished ones
├── docs/
│   ├── CLAUDE.md              # documentation rules
│   ├── principles.md          # the project's "constitution"
│   ├── glossary.md            # ubiquitous language
│   ├── decisions/             # ADRs (MADR 4) + generated decision log
│   ├── architecture/          # arc42-style overview with Mermaid C4 diagrams
│   ├── process/               # development workflow, documentation lifecycle, working with agents
│   ├── guides/                # how-to guides (created as needed)
│   ├── reference/             # facts to look up (created as needed)
│   ├── research/              # dated research notes and spikes
│   └── templates/             # ADR, guide, research note, living doc (also Obsidian templates)
├── .claude/                   # Claude Code: skills, /opsx commands (generated), docs-reviewer subagent, settings
├── .obsidian/                 # shared Obsidian settings (Markdown links, templates, exclusions)
├── .github/                   # PR/issue templates, CODEOWNERS, CI
└── scripts/                   # docs.mjs: ADRs, decision log, checks; openspec-update.mjs: regenerate /opsx commands
```

## Quick start

Requirements: Node.js 20.19 or later, and git. Obsidian is optional.

```bash
npm ci                                  # install doc/spec tooling (pinned versions)
npm run check                           # validate specs, lint Markdown, check decisions/links/freshness
npm run adr:new -- "Use PostgreSQL for persistence"   # record a decision (status: proposed)
npx openspec new change add-user-login  # start a change by hand (or /opsx:propose in your agent)
```

The `/opsx:*` slash commands call `openspec` directly, so install the CLI globally once: `npm install -g @fission-ai/openspec@1.13.2`.

Then, in Claude Code:

1. `/opsx:explore`: think an idea through
2. `/opsx:propose "..."`: proposal, delta specs, design and tasks
3. `/opsx:apply`: implement
4. `/opsx:archive`: merge the deltas into the specs

The full loop and definition of done are in [development workflow](docs/process/development-workflow.md).

## Using this as a template

1. On GitHub, open **Settings → General** and tick **Template repository**. Then create new projects with **Use this template**.
2. Follow [Start a new project from this template](docs/guides/start-a-new-project-from-this-template.md). It covers what to replace, what to keep and what to delete.

## Documentation map

- [AGENTS.md](AGENTS.md): start here, whether human or agent
- [Principles](docs/principles.md) · [Glossary](docs/glossary.md) · [Decision log](docs/decisions/README.md) · [Architecture](docs/architecture/README.md)
- Process: [Development workflow](docs/process/development-workflow.md) · [Documentation lifecycle](docs/process/documentation-lifecycle.md) · [Working with AI agents](docs/process/working-with-agents.md)
- Background: [Research on the standards behind this structure](docs/research/2026-09-30-standards-for-agent-maintained-project-knowledge.md)
