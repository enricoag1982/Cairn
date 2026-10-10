---
status: concluded
date: 2026-09-30
authors: [Claude Code]
related: [ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005]
---

# Research: standards for agent-maintained project knowledge

## Question

What are the current standards and good practices for a long-lived project where many decisions are made, documentation keeps changing, and AI coding agents do much of the work?
Specifically: agent guidance files, spec-driven development, decision records, documentation structure and Obsidian compatibility.
The findings shaped the initial structure of this repository.

## Findings

### Agent guidance files

- **AGENTS.md** is the cross-tool standard: plain Markdown with no required fields. It is stewarded by the Agentic AI Foundation under the Linux Foundation since December 2025. The nearest `AGENTS.md` to the edited file takes precedence, and explicit user prompts override everything.
  - Read natively by Codex, GitHub Copilot (cloud agent, CLI, VS Code), Cursor, Devin/Windsurf, Zed, Junie, Amp, Jules, goose and others.
  - Codex concatenates root-to-leaf `AGENTS.md` files, capped at 32 KiB by default.
- **Claude Code** reads `CLAUDE.md`. It also reads `AGENTS.md`, but by default only when no `CLAUDE.md` exists anywhere up the tree.
  - The recommended bridge is a `CLAUDE.md` that contains `@AGENTS.md` plus Claude-only notes. Nested `AGENTS.md` files then need a one-line `CLAUDE.md` next to them.
  - Anthropic recommends keeping each file under about 200 lines. Block-level HTML comments are stripped before injection.
- **Gemini CLI** reads `GEMINI.md` unless `.gemini/settings.json` sets `context.fileName` to include `AGENTS.md`.
- **Agent Skills** (agentskills.io) is an open standard adopted by 40+ clients: a `<name>/SKILL.md` with required `name` and `description` frontmatter, plus optional `scripts/`, `references/` and `assets/`.
  - Skills load progressively: metadata at startup, the body on activation.
  - Claude Code reads `.claude/skills/`. Codex and Gemini CLI read `.agents/skills/`. Cursor and Copilot read both.
- **Writing guidance** from Anthropic's Claude Code best practices, GitHub's analysis of 2,500 `agents.md` files, and HumanLayer:
  - Include exact commands (with flags, early in the file), how to test, the project structure, code style shown by example, git workflow, and three-tier boundaries (always / ask first / never).
  - Exclude anything the agent can infer from the code, generic advice, tutorials and fast-changing information.
  - Start small and add a line when an agent makes a mistake. Turn must-always rules into hooks or checks.
- **Evidence:** Gloaguen et al., *Evaluating AGENTS.md* (ETH Zurich, February 2026), found that context files often reduce task success and add more than 20% inference cost. LLM-generated files did worst. Their conclusion: human-written files with only minimal requirements.

### Spec-driven development

- **OpenSpec** (v1.13.2, npm `@fission-ai/openspec`) keeps the current behavior in `openspec/specs/<capability>/spec.md` and makes each change a folder.
  - A change folder holds a proposal, delta specs (`ADDED` / `MODIFIED` / `REMOVED` / `RENAMED` requirements), an optional design and tasks. Archiving merges the deltas into the specs and moves the folder to `changes/archive/YYYY-MM-DD-<id>/`.
  - Requirements use SHALL/MUST with `#### Scenario:` WHEN/THEN blocks, and `openspec validate --strict` checks them.
  - Since 1.0 ("OPSX"), project context lives in `openspec/config.yaml` and agent integration is generated as skills and slash commands per tool. It no longer writes to `AGENTS.md`.
- **GitHub Spec Kit** (v1.0, Python `specify-cli`) is built around a project "constitution" and per-feature folders `specs/NNN-feature/` (spec, plan, tasks, research, contracts), with clarify and analyze gates.
  - Specs are per feature and never merged into a current truth.
- **Kiro** writes per-feature `requirements.md` (EARS notation: "WHEN ... THE SYSTEM SHALL ..."), `design.md` and `tasks.md`, plus steering files in `.kiro/steering/`. It is tied to the Kiro IDE and CLI.
- **BMAD Method** does role-based planning (analyst, PM, architect) and is heavy. Tessl's "spec-as-source" is still early.
- For a long-lived, evolving project, a *spec-anchored* approach fits best: merged current specs plus reviewable deltas, which is what OpenSpec does.
  - OpenSpec lacks a project-wide decision log and principles, so we add ADRs and a principles page.

### Decisions

- **MADR 4.0.0** is the current MADR release.
  - YAML frontmatter: `status`, `date`, `decision-makers`, `consulted`, `informed`.
  - Sections: Context and Problem Statement, Decision Drivers, Considered Options, Decision Outcome (Consequences, Confirmation), Pros and Cons of the Options, More Information.
  - Files go in `docs/decisions/NNNN-title-with-dashes.md`.
- Good practice across MADR, Nygard, adr-tools, Log4brains and Azure guidance: the log is append-only. Only the status of an accepted ADR changes; a reversal is a new ADR that supersedes the old one.
- MADR has little tooling of its own, so a small repository script that generates the decision log from frontmatter and checks it in CI is the most robust option.

### Documentation structure

- **Diátaxis** separates tutorials, how-to guides, reference and explanation. It explicitly warns against creating empty folders for each type: structure should grow with content.
- **arc42** (v9) gives twelve sections for architecture documentation. A trimmed single page, with section 9 linking to the ADRs and section 12 linking to the glossary, suits a repository.
- **C4** levels 1–2 (context, containers) cover most needs. Mermaid flowcharts render on GitHub and in Obsidian; Mermaid's C4 syntax is experimental, and PlantUML and Structurizr do not render on GitHub.
- **Freshness:** Google's documentation practice (*Software Engineering at Google*, chapter 10) of an owner plus a last-reviewed date, with reminders, measurably keeps docs current. There is no standard tool, so a small CI check is the practical option.
- **Link checking:** lychee is the maintained choice for external links. The markdown-link-check GitHub Action is deprecated. Local links can be checked offline by a script.

### Obsidian compatibility

- Settings: turn off "Use `[[Wikilinks]]`", set "New link format" to relative path, and keep "Automatically update internal links" on.
- Commit `app.json`, `core-plugins.json` and `templates.json`. Git-ignore `workspace.json`, `workspace-mobile.json` and plugin data.
- Avoid Obsidian-only syntax in committed files: embeds, `%%comments%%`, highlights, block ids and inline fields.
  - GitHub alerts (`> [!NOTE]` etc.) render as Obsidian callouts.
  - Frontmatter shows up as Obsidian properties.
- **Bases** (a core plugin) and Dataview can query frontmatter, but neither renders on GitHub, so a generated Markdown index stays the source of truth.
- Repository root as vault vs `docs/` as vault: the root gives a complete graph (specs, changes, decisions) at the cost of indexing tooling and source folders. `docs/` is faster but cannot resolve links outside it.

## Options and trade-offs

The main trade-offs are recorded in the ADRs:

- decision format: [ADR-0001](../decisions/0001-record-architecture-decisions-with-madr.md)
- spec framework: [ADR-0002](../decisions/0002-use-openspec-for-spec-driven-development.md)
- agent guidance layout: [ADR-0003](../decisions/0003-use-agents-md-as-canonical-agent-guidance.md)
- Markdown and Obsidian: [ADR-0004](../decisions/0004-keep-docs-as-plain-markdown-browsable-in-obsidian.md)
- implementation language, left open: [ADR-0005](../decisions/0005-choose-primary-implementation-language.md)

## Conclusion

Adopted structure:

- OpenSpec for behavior
- MADR for decisions
- a trimmed arc42 page for architecture
- Diátaxis-style folders that are created only when there is content
- a canonical `AGENTS.md` with thin tool adapters and cross-tool skills
- plain Markdown that is Obsidian-friendly
- a small `npm run check` that enforces the rules that matter

Open follow-ups:

- Decide the implementation language (ADR-0005).
- Consider lychee for external links and Vale for terminology once there is enough prose.

## Sources

Most sources were read from their GitHub source repositories or official docs. A few sites could not be fetched from the research environment; those are marked *(unverified)* and were taken from search results.

- AGENTS.md: <https://agents.md/>, <https://github.com/agentsmd/agents.md>, Agentic AI Foundation <https://aaif.io/>
- Claude Code memory, skills, best practices: <https://code.claude.com/docs/en/memory>, <https://code.claude.com/docs/en/skills>, <https://code.claude.com/docs/en/best-practices>
- Agent Skills specification: <https://agentskills.io/>, <https://github.com/agentskills/agentskills>
- GitHub Copilot custom instructions: <https://docs.github.com/en/copilot> (read from the `github/docs` source repository)
- Gemini CLI context files: <https://github.com/google-gemini/gemini-cli>
- GitHub blog, "How to write a great agents.md: lessons from over 2,500 repositories": <https://github.blog/ai-and-ml/github-copilot/how-to-write-a-great-agents-md-lessons-from-over-2500-repositories/> (unverified)
- HumanLayer, "Writing a good CLAUDE.md": <https://www.humanlayer.dev/blog/writing-a-good-claude-md> (unverified)
- Gloaguen et al., "Evaluating AGENTS.md" (2026): <https://arxiv.org/abs/2602.11988> (unverified)
- OpenSpec: <https://github.com/Fission-AI/OpenSpec>, <https://www.npmjs.com/package/@fission-ai/openspec>
- GitHub Spec Kit: <https://github.com/github/spec-kit>
- Kiro specs and steering: <https://kiro.dev/docs/specs/>, <https://kiro.dev/docs/steering/> (unverified)
- BMAD Method: <https://github.com/bmad-code-org/BMAD-METHOD>
- MADR: <https://adr.github.io/madr/>, <https://github.com/adr/madr>
- Michael Nygard, "Documenting Architecture Decisions": <https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions>
- Log4brains: <https://github.com/thomvaill/log4brains>; adr-tools: <https://github.com/npryce/adr-tools>
- Diátaxis: <https://diataxis.fr/>
- arc42: <https://arc42.org/overview>; C4 model: <https://c4model.com/>
- Software Engineering at Google, chapter 10 (documentation): <https://abseil.io/resources/swe-book/html/ch10.html>
- Obsidian help (links, properties, Bases, templates): <https://help.obsidian.md/>
- markdownlint-cli2: <https://github.com/DavidAnson/markdownlint-cli2>; lychee: <https://github.com/lycheeverse/lychee>; Vale: <https://vale.sh/>
