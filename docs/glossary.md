---
last-reviewed: 2026-10-12
owner: ""
---

# Glossary

The project's shared vocabulary (the *ubiquitous language* in Domain-Driven Design terms).
Use these terms, spelled exactly like this, in specs, code, docs and conversations with agents.
When a new term shows up in a spec or a discussion, add it here in the same change.

<!-- Keep entries alphabetical. One or two sentences each. Link to the spec or ADR that defines the concept. -->

## Process terms

These come with the template. Add domain terms in their own section below.

| Term | Meaning |
| --- | --- |
| ADR | Architecture Decision Record: one file in `docs/decisions/` that records one significant decision, its context and its consequences. |
| Capability | A cohesive area of behavior with its own spec in `openspec/specs/<capability>/spec.md`. |
| Change | A proposed modification in `openspec/changes/<change-id>/`: proposal, delta specs, optional design, tasks. Archived when done. |
| Delta spec | The part of a change that says which requirements are ADDED, MODIFIED, REMOVED or RENAMED. Merged into the main specs on archive. |
| Living document | A document describing the current state. It is edited in place and carries `last-reviewed`. |
| Requirement | A normative statement (SHALL/MUST) inside a spec, with at least one scenario. |
| Research note | Non-normative exploration in `docs/research/`. Its conclusions move into an ADR or a spec. |
| Scenario | A concrete WHEN/THEN example that makes a requirement testable. |
| Supersede | Replace an accepted ADR with a newer one. The old one stays, with status `superseded by ADR-NNNN`. |

## Domain terms

Several definitions link to ADRs that are still `proposed`. The terms are agreed even where the design is not.

| Term | Meaning |
| --- | --- |
| Abstract type | An entity type with no entities of its own; its entities belong to its subtypes. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Association | A relationship type whose relationships are plain links between independent entities. Compare *composition*. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Attribute | A named, typed value of an entity or relationship, declared by its type or by a trait. |
| Basis | The number of a committed transaction. It identifies the snapshot that the transaction produced, so reads at the same basis see the same data. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Capability | A feature that a storage implementation may or may not support, discoverable at runtime. Asking for a missing one fails immediately. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Commit log | The append-only, ordered record of committed transactions, used for durability and recovery. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Composition | A relationship type whose target cannot exist without its source and ends together with it. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Conformance suite | The tests that every storage implementation must pass, built from the spec scenarios. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Context | The standard parameters of a request besides its own: time coordinates, principal, consistency, limits, tracing and features. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Effective time | When a fact is true in the real world. The writer sets it, in the past or in the future. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| End-date | To end an entity or relationship from a point in effective time. Nothing is deleted. |
| Entity | A thing with a stable identity, one entity type fixed when it is created, attributes and its own effective timeline. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity type | The definition of a kind of entity: attributes, keys, constraints, traits and at most one entity supertype. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity version | The complete state of one entity for one effective period, as recorded by one transaction. See [ADR-0009](decisions/0009-version-whole-entities-rather-than-single-attributes.md). |
| Feature flag | A named switch for gradual rollout or A/B testing. A gateway or flag service resolves it and passes the value in the context. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Patch | A write that sets some attributes for an effective period and leaves the others unchanged. The default write. See the [research note](research/2026-10-12-graph-system-of-record-api.md#writes). |
| Purge | A privileged, audited removal of chosen facts from all of history, for legal erasure. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Reference domain | The made-up enterprise model used in scenarios, tests and examples. See [reference domain](reference/reference-domain.md). |
| Relationship | A first-class, directed link between two entities, with its own identity, attributes and effective timeline. Stored once and navigable in both directions. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Relationship type | The definition of a kind of relationship: source and target entity types, cardinality, inverse name, and composition or association. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Replace | A write that sets the complete state for an effective period. Attributes not mentioned are removed. See the [research note](research/2026-10-12-graph-system-of-record-api.md#writes). |
| Role | An entity that models something another entity is only for a while, such as being a customer. It is linked by a relationship with its own effective period. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Schema | The entity types, traits and relationship types of a store. Stored as data and versioned in transaction time. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Snapshot | The state of the whole store after one commit, identified by its basis. |
| Trait | A named set of attribute declarations that entity types can include. It has no entities and no storage of its own. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Transaction | An atomic set of writes, committed by the single writer. It gets a transaction time and a basis. See [ADR-0007](decisions/0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md). |
| Transaction time | When sysRec recorded a fact. The engine sets it at commit, and it only increases. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| View | An immutable read handle created from a context. Its effective time, basis and meaning of "now" are fixed when it is created. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
