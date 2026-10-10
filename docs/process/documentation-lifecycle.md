---
last-reviewed: 2026-09-30
owner: ""
---

# Documentation lifecycle

This project changes its mind often, so documentation rots unless every document has a clear type, a clear home and a rule for how it changes.
This page defines those rules. `npm run check` enforces most of them.

## Document types

| Type | Location | Changes by | Normative? | Frontmatter |
| --- | --- | --- | --- | --- |
| Agent guide | `AGENTS.md`, `CLAUDE.md`, area `docs/CLAUDE.md` and `openspec/CLAUDE.md` | Edit in place | Yes, for agents | none |
| Spec | `openspec/specs/<capability>/spec.md` | Archiving an OpenSpec change (not edited by hand) | Yes | none (OpenSpec format) |
| Change | `openspec/changes/<id>/` | Edited while in flight, then archived and frozen | Proposal | `.openspec.yaml` |
| Decision (ADR) | `docs/decisions/NNNN-*.md` | Append-only: superseded, never rewritten once accepted | Yes | `status`, `date`, `decision-makers`, `consulted`, `informed` |
| Research note | `docs/research/YYYY-MM-DD-*.md` | Written once; status updated at the end | No | `status`, `date`, `authors`, `related` |
| Architecture | `docs/architecture/` | Edit in place (living) | Describes the current state | `last-reviewed`, `owner` |
| Guide (how-to) | `docs/guides/` | Edit in place (living) | No | `last-reviewed`, `owner` |
| Reference | `docs/reference/` | Edit in place (living) | Describes the current state | `last-reviewed`, `owner` |
| Process | `docs/process/` | Edit in place (living) | Yes, for contributors | `last-reviewed`, `owner` |
| Glossary | `docs/glossary.md` | Edit in place (living) | Yes, for vocabulary | `last-reviewed`, `owner` |
| Principles | `docs/principles.md` | Changed only through an ADR | Yes | `last-reviewed`, `owner` |

The `docs/` split follows [Diátaxis](https://diataxis.fr/): tutorials and how-tos live in `guides/`, `reference/` holds facts to look up, and explanation lives in `architecture/` and `decisions/`.
Create a folder only once it has content: empty placeholder folders help nobody.
Templates for each type are in [`docs/templates/`](../templates/).

## Three rules against rot

1. **One home per fact.** Behavior lives in specs, reasoning in ADRs, current structure in architecture docs. Everything else links to them instead of restating them. Duplicated text drifts; links break loudly.
2. **History is append-only, the present is edited in place.** ADRs, archived changes and research notes are records: they are superseded, not rewritten. Living documents describe *now*: fix them the moment they are wrong.
3. **Docs change in the same pull request as the thing they describe.** The PR template and the [definition of done](development-workflow.md#definition-of-done) ask for it.

## Review dates

Living documents carry `last-reviewed: YYYY-MM-DD`. Bump it whenever you check a document against reality, even if nothing changed.
`npm run docs:check` fails if the field is missing and warns when it is older than 180 days. `npm run docs:check -- --strict` turns warnings into failures.
The `owner` field names who to ask. Use a GitHub handle or team.

## Links

- Use standard relative Markdown links (`[ADR-0002](../decisions/0002-...md)`), never `[[wikilinks]]`. They render on GitHub, in IDEs and in Obsidian. `docs:check` rejects wikilinks and broken relative links.
- In OpenSpec change files, refer to ADRs by ID ("ADR-0002") rather than by link: archiving moves the change folder and would break relative links. Archived changes are not link-checked.
- Link to a spec by its folder: `openspec/specs/<capability>/spec.md`.

## Diagrams

Use [Mermaid](https://mermaid.js.org/) code blocks. GitHub, Obsidian and most IDEs render them, and they diff like text.
Follow the [C4 model](https://c4model.com/) levels (context, containers, components) in architecture docs.

## Browsing with Obsidian

The repository root is an [Obsidian](https://obsidian.md/) vault. Open the folder as a vault to get graph view, backlinks and search across specs, changes, decisions and docs.
The reasoning, and the alternative of a `docs/`-only vault, are in [ADR-0004](../decisions/0004-keep-docs-as-plain-markdown-browsable-in-obsidian.md).
The committed settings keep Obsidian compatible with GitHub:

- `.obsidian/app.json`:
  - `useMarkdownLinks: true` and `newLinkFormat: relative` make Obsidian write standard relative links, not wikilinks.
  - `alwaysUpdateLinks: true` makes renaming a file in Obsidian update the links to it.
  - Build and tooling folders (`node_modules/`, `build/`, `target/` and so on) are excluded from search and graph.
- `.obsidian/templates.json`: *Insert template* offers the files in `docs/templates` (ADR, guide, research note, living doc). Prefer `npm run adr:new` for ADRs, since it also numbers them and updates the log.
- `docs/decisions/decisions.base`: an Obsidian [Bases](https://help.obsidian.md/bases) table of all decisions, plus an "Open questions" view of `proposed` ones. It is a convenience; the generated [decision log](../decisions/README.md) stays authoritative.

Personal state (`workspace.json`, plugin data, `.trash/`) is git-ignored. Obsidian is optional: every file is plain Markdown and works in any editor.
If you use the obsidian-git plugin, turn off automatic commit-and-sync. Changes go through pull requests like everything else.

Obsidian-only syntax breaks on GitHub, so do not use it in committed files: `[[wikilinks]]`, `![[embeds]]`, `%%comments%%`, `==highlights==`, `^block-ids` and `key:: value` fields.
For callouts, use GitHub alerts (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`), which Obsidian renders too.

## Automated checks

| Command | Checks |
| --- | --- |
| `npm run specs:validate` | OpenSpec specs and in-flight changes are well-formed (`openspec validate --all --strict`) |
| `npm run docs:lint` | Markdown style ([markdownlint](https://github.com/DavidAnson/markdownlint-cli2), config in `.markdownlint-cli2.jsonc`) |
| `npm run docs:check` | ADR names, status and date; decision log up to date; relative links resolve; no wikilinks; review dates; skills in `.claude/skills/` have a valid `name` and `description`, and no duplicate `openspec-*` skills exist |
| `npm run check` | All of the above; CI runs it on every pull request |
