# Rules for docs/

Claude Code loads this file when working under `docs/`. The root [AGENTS.md](../AGENTS.md) still applies.
Full rationale: [documentation lifecycle](process/documentation-lifecycle.md).

## Where things go

- `decisions/`: ADRs (MADR 4). Create with `npm run adr:new -- "Title"`, never by copying a file by hand.
- `research/`: dated, non-normative notes, named `YYYY-MM-DD-topic.md`, from [the template](templates/research-note.md).
- `architecture/`, `guides/`, `reference/`, `process/`, `glossary.md` and `principles.md` are living documents: edit them in place. Changing `principles.md` needs an ADR.
- `templates/`: the templates themselves. Keep placeholders generic.

## Rules

- **ADRs:** once `accepted`, do not change the decision or its reasoning. Write a new ADR and set the old one's status to `superseded by ADR-NNNN`. Fixing typos and links is fine.
- **Decision log:** after changing an ADR's title, status or date, run `npm run docs:index`. Never edit the generated block by hand.
- **Living docs:** they need `last-reviewed: YYYY-MM-DD` and `owner` frontmatter. Bump `last-reviewed` whenever you verify or change the content.
- **One home per fact:** link to the spec, ADR or glossary entry instead of restating it.
- **Links:** use relative Markdown links only. Diagrams are Mermaid code blocks. Use GitHub alerts (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`) for callouts.
- **No Obsidian-only syntax** (it breaks on GitHub): no `[[wikilinks]]`, `![[embeds]]`, `%%comments%%`, `==highlights==`, `^block-ids`, or `key:: value` fields.
- **Style:** one sentence or short paragraph per line (no hard wrapping), `-` for bullets, sentence-case headings.
- Run `npm run docs:lint:fix && npm run docs:check` after editing.
