---
status: accepted
date: 2026-09-30
decision-makers: [project maintainers]
consulted: []
informed: []
---

# Keep all project knowledge as plain Markdown in the repository, browsable in Obsidian

## Context and Problem Statement

Specs, decisions, research and documentation will grow large and heavily cross-linked. People want a comfortable way to navigate them: graph, backlinks, search, templates.
Agents need the same knowledge as plain files in the repository, next to the code, versioned with it.
Where should documentation live, and in which format, so that it serves both?

## Decision Drivers

- Agents and CI read the same files humans do: no knowledge outside the repository
- Renders correctly on GitHub and in IDEs
- Comfortable navigation of a large, linked knowledge base
- No lock-in to a proprietary tool or format

## Considered Options

- Plain Markdown in the repository; Obsidian as an optional viewer with the repository root as vault
- Plain Markdown in the repository; Obsidian vault limited to `docs/`
- Obsidian-flavored Markdown (wikilinks, embeds)
- External wiki (Confluence, Notion, GitHub Wiki)
- Static documentation site generator as primary format

## Decision Outcome

Chosen option: "Plain Markdown in the repository; Obsidian as an optional viewer with the repository root as vault", because the graph across `openspec/specs`, `openspec/changes`, `docs/decisions` and the rest of `docs/` is exactly what makes a large knowledge base navigable. Only a root vault can resolve those links.

Rules:

- Standard CommonMark/GitHub Markdown only: relative `[text](path.md)` links, Mermaid diagrams, GitHub alerts (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`), which Obsidian renders as callouts.
- No Obsidian-only syntax in committed files: no `[[wikilinks]]`, `![[embeds]]`, `%%comments%%`, `==highlights==`, `^block-ids`, or inline `key:: value` fields.
- Metadata goes in YAML frontmatter, which Obsidian shows as properties and tooling can check.
- The committed `.obsidian/` config switches Obsidian to Markdown links with relative paths, uses `docs/templates` as the template folder, and excludes tooling folders. Personal workspace files are git-ignored.
- Generated indexes, such as the decision log, are plain Markdown tables. Obsidian Bases or Dataview queries are personal conveniences, never the only index.

### Consequences

- Good, because the same files work in GitHub, IDEs, agents, CI and Obsidian.
- Good, because Obsidian's graph and backlinks show how decisions, specs and changes relate.
- Bad, because Obsidian indexes the whole repository. Once there is a large source tree or tooling folders, startup gets slower. The exclusion list hides them from search and graph, but they are still scanned.
- Bad, because heading links written by Obsidian (`#Heading%20Text`) differ from GitHub anchors. Prefer file-level links.
- Neutral, because Obsidian stays optional. Nobody needs it to contribute.

### Confirmation

`npm run docs:check` rejects wikilinks and broken relative links. `npm run docs:lint` enforces Markdown style.

## Pros and Cons of the Options

### Obsidian vault limited to `docs/`

- Good, because it is fast and clean, and never scans source code or tooling.
- Bad, because links to `openspec/`, `AGENTS.md` or code do not resolve in Obsidian, so the most useful part of the graph is missing.
- Revisit this option if the root vault becomes slow. Switching needs no file changes.

### Obsidian-flavored Markdown

- Good, because wikilinks are shorter and rename-safe inside Obsidian.
- Bad, because they break on GitHub, in IDEs and for most tooling.

### External wiki

- Good, because non-developers are used to editing in a wiki.
- Bad, because agents and CI cannot see it, it is not versioned with the code, and it drifts.

### Static site generator as primary format

- Good, because it produces a polished published site.
- Bad, because of the extra build step and generator-specific syntax. A site can still be added later on top of the same Markdown.

## More Information

- Obsidian settings and usage: [documentation lifecycle](../process/documentation-lifecycle.md#browsing-with-obsidian)
- Research and sources: [2026-09-30 standards for agent-maintained project knowledge](../research/2026-09-30-standards-for-agent-maintained-project-knowledge.md)
