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

Each definition links to the ADR or spec that defines the concept. Some describe parts of the accepted design that are not built yet.

| Term | Meaning |
| --- | --- |
| Abstract type | An entity type with no entities of its own; its entities belong to its subtypes. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Adapter | A component outside the engine that offers a store's API in another form, such as the *agent tools* over the *Model Context Protocol*. An adapter acts as one *principal*, which it records on every transaction it submits. See [ADR-0007](decisions/0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md) and the `agent-tools` spec. |
| Agent tool | An operation of a store that an AI agent calls through the *Model Context Protocol*, such as `cairn_describe`, `cairn_dry_run` or `cairn_commit`. Agent tools take schema documents and transaction payloads as text and return JSON. See the `agent-tools` spec. |
| AI agent | A program driven by a language model. In Cairn's design, one that uses a running store as a first-class user: it reads the schema, plans and registers schema changes, and writes entities, through tools over the same API as everyone else. Compare *coding agent*. See [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md). |
| Alias | A previous name kept by a renamed type, attribute or inverse name. Looking up the alias finds the renamed element. See the `schema-evolution` spec. |
| Approval policy | The setting of an *adapter* that serves a store to AI agents that says, for schema registrations and for data commits, whether an agent's request is allowed, needs a person's approval, or is denied. Purges and migrations will come under it once they exist. See [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md) and the `agent-tools` spec. |
| Approval request | A request of an AI agent that waits for a person's approval under the *approval policy*. It keeps its ID, the request, who asked, for whom, why and when; a person approves or rejects it, approving applies it at that moment, and the request then keeps the outcome. See the `agent-tools` spec. |
| Association | A relationship type whose relationships are plain links between independent entities. Compare *composition*. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Attribute | A named, typed value of an entity or relationship, declared by its type or by a trait. |
| Available rule | A rule that a schema registry was given, described by its name, version, description, the type it applies to and the problem codes it reports. A schema may name only available rules. A store's available rules are its rules and its *declared rules*. See the `type-model` spec. |
| Basis | The number of a committed transaction. It identifies the snapshot that the transaction produced, so reads at the same basis see the same data. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Breaking change | A schema change that existing data or users cannot follow, such as removing an attribute. A registration with a breaking change is rejected. See the `schema-evolution` spec. |
| Coding agent | An AI agent that writes code and documents in a repository, such as this one or an application that embeds Cairn. Its changes reach production through code review. Compare *AI agent*. |
| Commit log | The append-only, ordered record of committed transactions, used for durability and recovery. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Compatible change | A schema change that existing data and users can follow, such as adding an optional attribute. See the `schema-evolution` spec. |
| Composition | A relationship type whose target cannot exist without its source and ends together with it. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Composition cascade | Ending, from the same instant and in the same transaction, the composition relationships of an entity that ends and their targets, recursively. The receipt lists what it ended. See the `relationships` spec. |
| Conflict check | A check a write can ask for, against the basis at which its caller read the entity: per entity, or per attribute the write sets or clears. Without one, the last commit wins. See the `entity-versions` spec and [ADR-0009](decisions/0009-version-whole-entities-rather-than-single-attributes.md). |
| Conformance suite | The tests that every storage implementation must pass, built from the spec scenarios. See [ADR-0011](decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md). |
| Constraint | A limit that a schema definition declares, such as required, unique, a range, allowed values, a pattern, a length or a multiplicity, and that the engine checks on every write. Logic that a constraint cannot express is a *rule*. See [ADR-0018](decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md). |
| Context | The standard parameters that accompany every request besides its own, such as time coordinates, the *principal* and feature flags. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Coordinates | Where a view reads: an effective time, and a basis or transaction time to read as of (*known at*), the latest by default. See the `views` spec. |
| Correction | A write that changes what was recorded for an effective period in the past or future. Earlier knowledge stays readable at earlier bases. See the `entity-versions` spec. |
| Declared rule | A rule a store knows only by its description, without its code. Schemas may name it, but transactions that write entities or relationships of the types that name it are rejected. See the `entity-rules` spec. |
| Deprecated | Marks a type or attribute that should no longer be used. It stays in the schema with its history and is never removed. See the `type-model` spec. |
| Document location | Where a problem is in a schema document or a transaction payload: a JSON Pointer, a line and a column, counted from 1. See the `schema-documents` and `json-payloads` specs. |
| Dry run | Validating a transaction against the state it would produce, reporting what committing would report, without recording anything, using a basis or reserving an ID. See the `transactions` spec. |
| Effective period | A half-open interval of effective time, `[from, until)`, with microsecond precision; an open end means until further notice. Every write applies to one. See the `entity-versions` spec. |
| Effective time | When a fact is true in the real world. The writer sets it, in the past or in the future. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| End | One of the two entities a relationship links: its source or its target. Not to be confused with *end-date*. See the `relationships` spec. |
| End-date | To end an entity or relationship from a point in effective time. Nothing is deleted. See the `entity-versions` spec. |
| Entity | A thing with a stable identity, one entity type fixed when it is created, attributes and its own effective timeline. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity ID | The opaque ID an entity keeps for its whole life, given by the store's *ID generator* or supplied by the caller in the store's format. Not to be confused with schema identifiers. See [ADR-0019](decisions/0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md). |
| Entity type | The definition of a kind of entity: attributes, constraints, traits and at most one entity supertype. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Entity version | The complete state of one entity for one effective period, as recorded by one transaction. See the `entity-versions` spec and [ADR-0009](decisions/0009-version-whole-entities-rather-than-single-attributes.md). |
| Feature flag | A named switch for gradual rollout or A/B testing. A gateway or flag service resolves it and passes the value in the context. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Format version | The version of the format of a schema document or transaction payload, given by the key `cairn`. Only version 1 exists. See the `schema-documents` and `json-payloads` specs. |
| Generated type | A Java interface generated from schema documents at build time for an entity type, trait or relationship type, with an accessor per attribute; entity and relationship types also get a *snapshot record* and builders of writes. The engine never depends on it. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md), [ADR-0021](decisions/0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md) and the `java-types` spec. |
| Generic API | The API that reads and writes entities of any type without generated types, for example as JSON payloads. Types registered at runtime without generated types use it. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| ID generator | What gives new entities and relationships their IDs. A store has exactly one, chosen when it is created: UUIDv7 by default. See [ADR-0019](decisions/0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md). |
| Idempotency key | A key a caller gives a transaction so that retrying it is safe: the same transaction again returns the first receipt, and the key with other content is rejected. See the `transactions` spec. |
| Inverse name | The name for navigating a relationship type from its target to its source, such as `ownedBy` for Owns. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Lifetime | The effective time in which an entity or relationship exists: the union of its versions' periods. A relationship must lie within the lifetimes of its ends. See the `relationships` spec. |
| Link | One result of navigating from an entity: the relationship followed and the entity at its other end. See the `relationships` spec. |
| Model Context Protocol (MCP) | The open protocol through which AI agents discover and call tools. Cairn serves a store's *agent tools* over it. See the `agent-tools` spec. |
| Multiplicity | How many relationships of one type an entity can have at one end: a minimum of 0 or 1 and a maximum of 1 or unbounded. A relationship type has two, targets per source and sources per target. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Origin | What registers a set of types, identified by a name and a version, such as an application's schema documents and its release. Each type records its origin, with the version of the origin's latest applied registration. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Patch | A write that sets some attributes for an effective period and leaves the others unchanged. The default write. See the `entity-versions` spec. |
| Plan | What a registration would do, worked out without applying it: apply, leave the schema unchanged or reject, with every classified difference and every problem. See the `schema-evolution` spec. |
| Principal | Who submits a transaction or a read: a person, an application or an AI agent. A transaction also records whom the principal acts for, if anyone. See the `transactions` spec. |
| Problem | One reason a schema document, a schema definition, a registration, a transaction or a call of an *agent tool* is rejected, with a stable code, the path of the element concerned and a message. Where it applies, it also says what was expected, what was found and how to fix it, where it is in a schema document or transaction payload (its *document location*), and, for a problem a rule reports, the rule's own code. See the `type-model` spec and [ADR-0017](decisions/0017-design-cairn-so-that-ai-agents-are-first-class-users.md). |
| Purge | A privileged, audited removal of chosen facts from all of history, for legal erasure. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Range | The interval that values of an ordered attribute (a number, date or instant) must lie in: an optional lower and an optional upper bound, each inclusive or exclusive. Not to be confused with the allowed values of a string attribute. See the `type-model` spec. |
| Receipt | The result of a committed transaction: its basis and transaction time, the IDs of the entities and relationships it created and of those that composition cascades ended, and who and why. See the `transactions` spec. |
| Reference domain | The made-up enterprise model used in scenarios, tests and examples. See [reference domain](reference/reference-domain.md). |
| Registration | Submitting the complete set of one origin's types to a schema registry. It is applied as a schema transaction, reported as unchanged, or rejected with every problem. See the `schema-evolution` spec. |
| Relate | The write that creates a relationship between a source and a target entity for an effective period. See the `relationships` spec. |
| Relationship | A first-class, directed link between two entities, with its own identity, attributes and effective timeline. Stored once and navigable in both directions. It must lie within the lifetimes of both entities it links. See the `relationships` spec and [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Relationship ID | The opaque ID a relationship keeps for its whole life, from the store's ID generator, in the same namespace as entity IDs. See [ADR-0019](decisions/0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md). |
| Relationship type | The definition of a kind of relationship: source and target entity types, two multiplicities, inverse name, and composition or association. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Replace | A write that sets the complete state for an effective period. Attributes not mentioned are removed. See the `entity-versions` spec. |
| Role | An entity that models something another entity is only for a while, such as being a customer. It is linked by a relationship with its own effective period. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Rule | Validation logic written in Java, with a name, a version and a description, that an entity type or relationship type names by name and version. It applies to the type's subtypes too, runs at commit and reports problems with its own codes. Compare *constraint* and *declared rule*. See the `entity-rules` spec and [ADR-0018](decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md). |
| Rule base class | An abstract Java class generated for a rule that a schema document names, which holds the rule's name, version and type and passes each version to a typed check; the rule's code extends it. See [ADR-0021](decisions/0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md). |
| Rule context | What a rule gets to read the state a transaction would produce, at any effective time, and to report problems. It cannot write. See the `entity-rules` spec. |
| Schema | The entity types, traits and relationship types of a store. Stored as data and versioned in transaction time. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Schema definition | A schema written as plain data, before it is validated and registered. Compare *schema*. See the `type-model` spec. |
| Schema document | The canonical text form of one origin and its schema definition, in YAML 1.2 (which includes JSON), described by a published JSON Schema. Read strictly: every problem is located in the document. See [ADR-0012](decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md). |
| Schema identifier | The number a store gives a type or attribute when it is first registered. It never changes, is never reused, and is local to the store. Not to be confused with entity IDs. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Schema registry | What validates, plans and registers schema definitions against the rules available to it, and keeps the history of the schema in transaction time. See the `schema-evolution` spec. |
| Schema transaction | A transaction that changes the schema. Like any transaction, it gets a transaction time and a basis, and records its principal, the party it acts for and a reason. See the `schema-evolution` spec. |
| Snapshot | The state of the whole store after one commit, identified by its basis. Compare *snapshot record*. |
| Snapshot record | The immutable record that a *generated type* reads an entity or relationship version into, with the generic state it came from and every attribute of the type. Compare *snapshot*. See [ADR-0021](decisions/0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md). |
| Storage capability | A feature that a storage implementation may or may not support, discoverable at runtime. Asking for a missing one fails immediately. See [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Store | Where entities and their history live, with its own schema registry, ID generator and single writer. Created in memory today. See the `transactions` spec. |
| Stored definition | The origin and schema definition of an origin's latest applied registration, as the schema registry returns it, for example to export it as a schema document. See the `schema-evolution` spec. |
| Supertype | The type an entity type or relationship type extends, and whose attributes it inherits. Each type has at most one direct supertype; its supertypes are that type and, transitively, the supertypes of that type. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Supplied ID | An entity ID that the caller gives when creating an entity, for example to repeat an import safely. Creating an entity whose ID exists is a problem. See the `transactions` spec. |
| Symmetric relationship type | A relationship type whose two ends are interchangeable, such as "related party". See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Temporary ID | A name a transaction gives an entity or relationship it creates, so that its other writes can refer to it. The receipt maps it to the ID. See the `transactions` spec. |
| Timeline | The versions of one entity or relationship across effective time, as known at one basis. See the `views` spec. |
| Trait | A named set of attribute declarations that entity types can include. It has no entities and no storage of its own. See [ADR-0010](decisions/0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md). |
| Transaction | An atomic set of writes, committed by the single writer. It gets a transaction time and a basis. See the `transactions` spec and [ADR-0007](decisions/0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md). |
| Transaction payload | A transaction written as text, in YAML 1.2 or JSON, as agents and tools send it. Read with the schema to type its values; its problems are located in it. See the `json-payloads` spec. |
| Transaction time | When Cairn recorded a fact. The engine sets it at commit, and it only increases. See [ADR-0008](decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md). |
| Traversal | Navigating relationships recursively from an entity, by one name, following only relationships that meet a condition, up to a maximum depth and visiting each entity once. See the `relationships` spec. |
| Value type | The kind of value an attribute holds: string, boolean, int, long, decimal, date or instant. See the `type-model` spec. |
| View | An immutable read handle created from a context. Its effective time, basis and meaning of "now" are fixed when it is created, so every read through it is repeatable. See the `views` spec and [ADR-0013](decisions/0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md). |
| Write | One change in a transaction: create, relate, patch, replace or end-date, each for an effective period. See the `entity-versions` spec. |
