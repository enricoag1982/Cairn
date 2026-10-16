# Proposal

## Why

Both ends of a relationship must exist at every point of its effective period, so ending an entity ends every relationship to it. Some relationships record a past fact instead: who decided a leave request. In the HR example, ending a manager's employment would end every `DecidedBy` pointing to it, and past decisions would drop out of current views; so the generated company never ends a manager's employment. The HR domain page lists this gap: "Relationships that record past facts end with their ends".

## What Changes

- A relationship type MAY declare `outlivesTarget`: its relationships may continue after their target ends. Compositions and symmetric types cannot.
- The target of such a relationship exists at the start of its effective period. Ending the target does not require ending the relationship, and a composition cascade that ends the target leaves it.
- Multiplicities at the target end hold while the target exists; at the source end the relationship counts for as long as it is effective.
- Navigating from the source returns the target's last version, whose period shows that it has ended; queries that follow the relationship read the same version, and traversals reach it and go no further.
- Declaring `outlivesTarget` on an existing type is compatible; removing it is breaking.
- Schema documents, their JSON Schema, stored definitions, the agent tools' descriptions and generated Java types carry the declaration.
- The HR example's `DecidedBy` outlives its target: HR-17 ends Cleo's employment, and her decision of Dev's vacation stays. The generated company ends a manager's employment too.

### Non-goals

- Relationships that outlive their source.
- Compositions that outlive their parts.
- Reading the last version of an ended entity other than through a relationship that outlives it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `type-model`: the `outlivesTarget` declaration of relationship types.
- `relationships`: lifetimes, cascades, multiplicities, navigation and traversal for types that outlive their targets.
- `schema-evolution`: declaring and removing `outlivesTarget`.
- `schema-documents`: the default of `outlivesTarget`.

## Impact

- **Decisions:** ADR-0034, accepted under the standing approval: relationships of a type may outlive their targets, as records of past facts. It relaxes the `relationships` spec's lifetime rule for those types; no accepted ADR changes.
- **Code:** `cairn-api` (definitions, builder, resolved type); `cairn-core` (validation, compilation, compatibility, stored definitions, integrity, cascades, multiplicities, views and queries); `cairn-documents` (reading, writing, JSON Schema); `cairn-agent-tools` (descriptions); `cairn-codegen` (Javadoc of generated relationship types); `cairn-example-hr` (`DecidedBy`, HR-17, the generated company).
- **Docs:** the glossary, the HR domain page (type table, HR-17, gaps), the reference domain page (the test-only types), the architecture page.
