---
last-reviewed: 2026-10-13
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

Several definitions link to ADRs that are still `proposed`, or name specs that the OpenSpec change `add-type-model` proposes. The terms are agreed even where the design is not.

| Term | Meaning |
| --- | --- |
| Abstract type | An entity type with no entities of its own; its entities belong to its subtypes. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| AI agent | A program driven by a language model. In Cairn's design, one that uses a running store as a first-class user: it reads the schema, plans and registers schema changes, and writes entities, through tools over the same API as everyone else. Compare *coding agent*. See [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md). |
| Alias | A previous name kept by a renamed type, attribute or inverse name. Looking up the alias finds the renamed element. See the `schema-evolution` spec. |
| Approval policy | The setting of a store that says which changes an AI agent may apply on its own and which need a person's approval, such as purges and, once migrations exist, breaking schema changes. See [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md). |
| Association | A relationship type whose relationships are plain links between independent entities. Compare *composition*. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Attribute | A named, typed value of an entity or relationship, declared by its type or by a trait. |
| Basis | The number of a committed transaction. It identifies the snapshot that the transaction produced, so reads at the same basis see the same data. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Breaking change | A schema change that existing data or users cannot follow, such as removing an attribute. A registration with a breaking change is rejected. See the `schema-evolution` spec. |
| Coding agent | An AI agent that writes code and documents in a repository, such as this one or an application that embeds Cairn. Its changes reach production through code review. Compare *AI agent*. |
| Commit log | The append-only, ordered record of committed transactions, used for durability and recovery. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Compatible change | A schema change that existing data and users can follow, such as adding an optional attribute. See the `schema-evolution` spec. |
| Composition | A relationship type whose target cannot exist without its source and ends together with it. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Conformance suite | The tests that every storage implementation must pass, built from the spec scenarios. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Constraint | A limit that a schema document declares, such as required, unique, a range, a pattern or a multiplicity, and that the engine checks on every write. Logic that a constraint cannot express is a *rule*. See [ADR-0018](decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md). |
| Context | The standard parameters that accompany every request besides its own, such as time coordinates, the caller and feature flags. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Deprecated | Marks a type or attribute that should no longer be used. It stays in the schema with its history and is never removed. See the `type-model` spec. |
| Effective time | When a fact is true in the real world. The writer sets it, in the past or in the future. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| End-date | To end an entity or relationship from a point in effective time. Nothing is deleted. See the [research note](research/2026-10-12-graph-system-of-record-api.md#writes). |
| Entity | A thing with a stable identity, one entity type fixed when it is created, attributes and its own effective timeline. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity type | The definition of a kind of entity: attributes, constraints, traits and at most one entity supertype. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity version | The complete state of one entity for one effective period, as recorded by one transaction. See [ADR-0009](decisions/0009-version-whole-entities-rather-than-single-attributes.md). |
| Feature flag | A named switch for gradual rollout or A/B testing. A gateway or flag service resolves it and passes the value in the context. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Generated type | A Java type generated from a schema document at build time, giving typed access to the entities of one entity type. The engine never depends on it. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Generic API | The API that reads and writes entities of any type without generated types, for example as JSON payloads. Types registered at runtime without generated types use it. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Inverse name | The name for navigating a relationship type from its target to its source, such as `ownedBy` for Owns. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Multiplicity | How many relationships of one type an entity can have at one end: a minimum of 0 or 1 and a maximum of 1 or unbounded. A relationship type has two, targets per source and sources per target. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Origin | What registers a set of types, identified by a name and a version, such as an application's schema documents and its release. Each type records its origin, with the version of the origin's latest applied registration. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Patch | A write that sets some attributes for an effective period and leaves the others unchanged. The default write. See the [research note](research/2026-10-12-graph-system-of-record-api.md#writes). |
| Problem | One reason a schema definition, a registration or, later, a write is rejected, with a stable code, the path of the element concerned and a message. See the `type-model` spec and [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md). |
| Purge | A privileged, audited removal of chosen facts from all of history, for legal erasure. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Range | The allowed values of an ordered attribute (a number, date or instant): an optional lower and an optional upper bound, each inclusive or exclusive. See the `type-model` spec. |
| Reference domain | The made-up enterprise model used in scenarios, tests and examples. See [reference domain](reference/reference-domain.md). |
| Registration | Submitting the complete set of one origin's types to a schema registry. It is applied as a schema transaction, reported as unchanged, or rejected with every problem. See the `schema-evolution` spec. |
| Relationship | A first-class, directed link between two entities, with its own identity, attributes and effective timeline. Stored once and navigable in both directions. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Relationship type | The definition of a kind of relationship: source and target entity types, two multiplicities, inverse name, and composition or association. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Replace | A write that sets the complete state for an effective period. Attributes not mentioned are removed. See the [research note](research/2026-10-12-graph-system-of-record-api.md#writes). |
| Role | An entity that models something another entity is only for a while, such as being a customer. It is linked by a relationship with its own effective period. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Rule | Validation logic written in Java, with a name, a version and a description, that a schema document attaches to an entity type or relationship type by name. Rules run at commit and report problems. Compare *constraint*. See [ADR-0018](decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md). |
| Schema | The entity types, traits and relationship types of a store. Stored as data and versioned in transaction time. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Schema definition | A schema written as plain data, before it is validated and registered. Compare *schema*. See the `type-model` spec. |
| Schema document | The canonical text form of one origin's schema definition, written in YAML by people or exchanged as JSON by tools, and described by a published JSON Schema. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Schema identifier | The number a store gives a type or attribute when it is first registered. It never changes, is never reused, and is local to the store. Not to be confused with entity IDs. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Schema registry | What validates and registers schema definitions, and keeps the history of the schema in transaction time. See the `schema-evolution` spec. |
| Schema transaction | A transaction that changes the schema. Like any transaction, it gets a transaction time and a basis. See the `schema-evolution` spec. |
| Snapshot | The state of the whole store after one commit, identified by its basis. |
| Storage capability | A feature that a storage implementation may or may not support, discoverable at runtime. Asking for a missing one fails immediately. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Supertype | The type an entity type or relationship type extends, and whose attributes it inherits. Each type has at most one direct supertype; its supertypes are that type and, transitively, the supertypes of that type. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Symmetric relationship type | A relationship type whose two ends are interchangeable, such as "related party". See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Trait | A named set of attribute declarations that entity types can include. It has no entities and no storage of its own. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Transaction | An atomic set of writes, committed by the single writer. It gets a transaction time and a basis. See [ADR-0007](decisions/0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md). |
| Transaction time | When Cairn recorded a fact. The engine sets it at commit, and it only increases. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Value type | The kind of value an attribute holds: string, boolean, int, long, decimal, date or instant. See the `type-model` spec. |
| View | An immutable read handle created from a context. Its effective time, basis and meaning of "now" are fixed when it is created. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
