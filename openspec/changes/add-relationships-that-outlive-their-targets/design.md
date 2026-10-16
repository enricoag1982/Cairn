# Design

## Context

ADR-0034 decides this change: an association that is not symmetric may declare that its relationships outlive their targets. The target must exist at the start of the relationship, and reads return its last version afterwards.

Today, `Integrity` checks at commit that both ends of every written relationship exist throughout its period, and that an entity a transaction ends has no relationship continuing past its end. `Cascades` ends composition targets with their sources, and every other relationship of those targets. Multiplicities are checked per end over each entity's lifetime. Views navigate from an entity that exists at the view's effective time and return each relationship with the state of the other end at that time; traversals and query follow steps use navigation. The `symmetric` flag of relationship types already runs through every layer (definitions, builder, documents, JSON Schema, canonical form, validation, compilation, stored definitions, describe, codegen), and the new flag follows its path.

## Goals / Non-Goals

**Goals:** relationships that record a past fact stay effective, and readable from their source, after their target ends, with integrity checked as far as it still applies.

**Non-goals:** see the proposal.

## Decisions

### D1. The declaration

- **Definitions.** `RelationshipTypeDefinition` gains a boolean component `outlivesTarget`, after `symmetric`, default `false`; the existing constructors keep compiling, without it. `RelationshipTypeBuilder.outlivesTarget()` sets it.
- **The resolved type.** `RelationshipType.outlivesTarget()` reports it.
- **Validation.** A composition, or a symmetric type, that declares it is a problem at the type, with the code that other invalid combinations of relationship properties use.
- **Subtypes.** The flag belongs to each type and is not inherited. A subtype and its supertype may differ, because integrity is checked per concrete relationship type.
- **Documents.** In a schema document, `outlivesTarget: true`, default `false`. The document writer, the published JSON Schema of documents, and the canonical form carry it.
- **Stored definitions.** Schema records keep it in an optional trailing section of format version 1, as checks and derived attributes do, so older records read as `false`.
- **Agent tools and codegen.** The type descriptions of the agent tools include it. The Javadoc of a generated relationship type says that its relationships may outlive their targets, and that navigation then returns the target's last version.

### D2. Integrity

For a type that outlives its target:

- **The source** must exist at every point of the relationship's period, as for every type.
- **The target** must exist at the start of each stretch of the relationship's effective period; a stretch is a maximal run of adjacent segments. A relationship that starts before its target exists, or after it ended, is rejected with the problem that relationships outside their ends' lifetimes give today.
- **Ending the target** does not require ending these relationships, and a target that a correction ends earlier is checked again only against the starts of the stretches.
- **Cascades.** A composition cascade that ends an entity leaves the relationships that outlive it as their target; those where it is the source end as before.

### D3. Multiplicities

At the source end, a relationship counts for as long as it is effective, whether its target exists or not. At the target end, the multiplicities of an entity hold over its lifetime, as for every entity, so relationships that continue after it ended are not counted against it.

### D4. Reads

- **Navigation.** Navigating from the source by the type's name returns, for a relationship whose target does not exist at the view's effective time, the target's last version as known at the view's basis: the version whose period ends last before the view's effective time. A caller tells it by its period, which ends at or before the view's effective time. `Link` does not change.
- **From the target.** Navigating from an entity that does not exist at the view's effective time finds nothing, as today, so no read reaches relationships from an ended target.
- **Queries and traversals.** Follow steps bind the same version, so filters and projections read its values. Traversals reach it and go no further, because navigation from it finds nothing.
- **Reads that rules record.** Reading the last version reads the target's timeline, which is recorded as a timeline read (ADR-0025), so a correction of that timeline runs the rules that read it again.
- **Everywhere.** The same applies in every view, including the provisional view of rules, views at older bases, and every kind of store.

### D5. Schema evolution

Declaring `outlivesTarget` on a relationship type that the stored schema already has is compatible. Removing it is breaking, with a hint to keep it while stored relationships outlive their targets; adding a new type that declares it is compatible as adding any type is.

### D6. The reference domain and the HR example

- **Test-only types.** Scenarios use the test-only association `OpenedBy` from Account to Person, with 0..1 targets per source and 0..* sources per target, which outlives its target, and a variant of `MailedTo` that outlives its target. Each scenario that needs one names it.
- **`DecidedBy`.** The HR schema declares `outlivesTarget: true` for `DecidedBy`.
- **HR-17.** Recorded 1 November: Cleo leaves on 30 November, her employment and its other relationships end, and her decision of Dev's vacation (HR-5) stays. Effective 1 December, navigating Dev's request's `decidedBy` returns Cleo's employment as it last was, with a period that ends on 1 December.
- **The generated company (HR-14)** may end managers' employments: a later decision in their units goes to a manager of a unit above who exists on the request's first day, as `decided-by-manager` requires.

## Risks / Trade-offs

- **Readers that assumed both ends exist.** Code that reads a target through a relationship of such a type may receive an ended version. Only types that declare the flag behave so, and the Javadoc of their generated types says so.
- **Reading last versions costs a timeline read** for each ended target that navigation reaches. Few relationships outlive their targets, so this is acceptable.
