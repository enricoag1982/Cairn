---
last-reviewed: 2026-09-30
owner: ""
---

# Start a new project from this template

How to turn a fresh copy of this template into your project's repository.

## Before you start

- A new repository created from this template (GitHub **Use this template**), cloned locally
- Node.js 20.19 or later
- Optional: Obsidian. Recommended: Claude Code, the agent this template is set up for

## Steps

1. **Install and verify the tooling.**

   ```bash
   npm ci
   npm run check
   ```

2. **Describe the project in `AGENTS.md`.** Replace the "Project overview" paragraph with what the product is, who it is for and what makes it hard. Keep it to a few lines.

3. **Set the principles.** In [`docs/principles.md`](../principles.md), keep or adapt the working principles and write the product and engineering principles, in priority order.

4. **Seed the glossary.** Add the first ten or so domain terms to [`docs/glossary.md`](../glossary.md). Agents use these words verbatim.

5. **Review the template's decisions.** ADR-0001 to ADR-0006 explain this structure; the [decision log](../decisions/README.md) shows which are accepted, open or superseded. Keep the ones you agree with. For any you change, write a superseding ADR rather than editing them.
   Fill in `decision-makers` with real names or handles.

6. **Decide or schedule ADR-0005 (implementation language).** If you decide now, follow the checklist in its "Decision Outcome". Otherwise leave it `proposed` and set the date to revisit it.

7. **Tailor `openspec/config.yaml`.** Adjust `context` (keep it short) and the per-artifact `rules`.

8. **Check the agent setup.** Claude Code is set up (see [ADR-0006](../decisions/0006-target-claude-code-without-duplicated-agent-files.md)). To support another tool, see [working with AI agents](../process/working-with-agents.md#adding-another-ai-tool).

9. **Set ownership.** Uncomment and fill in [`.github/CODEOWNERS`](../../.github/CODEOWNERS), and set `owner` in the living documents.

10. **Protect `main`.** In GitHub branch protection or rulesets, require pull requests and the `check` workflow.

11. **Write the first change.** Use `/opsx:propose` (or `npx openspec new change <id>`) for the first capability, and walk it through the [development workflow](../process/development-workflow.md).

12. **Rewrite `README.md`** for your project, and delete this guide if you like.

## Verify

- `npm run check` passes locally and in CI.
- `npx openspec list --specs` shows your first capability after the first change is archived.
- In Claude Code, `/opsx:propose` is available and `record-decision` appears among the skills, each listed once.

## Related

- [Development workflow](../process/development-workflow.md)
- [Documentation lifecycle](../process/documentation-lifecycle.md)
- [ADR-0005: implementation language](../decisions/0005-choose-primary-implementation-language.md)
