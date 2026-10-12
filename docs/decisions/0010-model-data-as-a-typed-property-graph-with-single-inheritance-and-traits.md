---
status: proposed
date: 2026-10-12
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Model data as a typed property graph with single inheritance and traits

## Context and Problem Statement

sysRec must let users define enterprise data with inheritance and relationships.
We need one meta-model that every type is defined in, and that the engine, the API and the storage encoding all build on.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Familiar to Java developers and data modelers
- Relationships are as important as entities and have their own history
- Polymorphic queries ("all parties") without ambiguity
- Types evolve without rewriting stored data
- Things that come and go over time do not require an entity to change type

## Considered Options

- Typed property graph with single inheritance and traits
- Typed property graph with multiple inheritance
- RDF triples or quads
- Datoms (entity, attribute, value, transaction) as in Datomic
- Documents with references

## Decision Outcome

Proposed option: "Typed property graph with single inheritance and traits", because it matches how enterprise data is usually modeled and keeps inheritance unambiguous.

### Entities and entity types

- An entity has a stable identity, exactly one entity type, attributes and its own effective timeline.
- An entity's type is fixed when it is created.
- An entity type has at most one entity supertype. It can be abstract, which means it has no entities of its own.
- A **trait** declares attributes that any number of entity types can include. A trait has no entities and no storage of its own.
- Queries on a type include its subtypes by default, with an option to match the exact type only. The engine expands a type to its subtypes when a query runs, so entities are indexed under their concrete type only.

### Relationships and relationship types

- A relationship is a first-class, directed link between two entities, with its own identity, attributes and effective timeline.
- A relationship is stored once. The engine maintains the reverse index, so it can be navigated in both directions at the same cost. The schema names the inverse direction.
- A relationship type declares its source and target entity types (subtypes are accepted), its inverse name and two **multiplicities**: how many targets each source can have, and how many sources each target can have. Each is a minimum of 0 or 1 and a maximum of 1 or unbounded. Multiplicities are checked at every point in effective time.
- A relationship type is either a **composition**, where the target cannot exist without the source and ends with it, or an **association**, a plain link.
- A relationship type can be declared symmetric. Its two ends then form an unordered pair.
- Relationship types can also have one relationship supertype.
- A relationship between more than two entities is modeled as an entity with binary relationships.

### Roles and the schema

- Something an entity is only for a while, such as being a customer, is modeled as a **role**: an entity linked to it by a relationship with its own effective period.
- The schema is itself data in the store, versioned in transaction time only. Schema changes are transactions and are checked for compatibility before they are applied.
- Every type and attribute gets a **schema identifier** when it is first registered. It is local to the store and never changes or gets reused. Names are labels: a rename keeps the identifier and keeps the old name as an alias. Stored data refers to schema identifiers, never to names; [ADR-0009](0009-version-whole-entities-rather-than-single-attributes.md) puts them in every stored field.

### Consequences

- Good, because the model is close to class diagrams, the Java type system and ISO GQL.
- Good, because inheritance has no diamond conflicts between attribute definitions or constraints.
- Good, because "what type was this entity on 1 January?" never needs asking.
- Bad, because a type that "is" two things must use a trait or a role instead of a second supertype.
- Bad, because roles add entities and relationships compared with changing the type in place.

### Confirmation

The type-model specification and its conformance scenarios cover inheritance, traits, polymorphic queries, relationship multiplicities and inverse navigation.

## Pros and Cons of the Options

### Typed property graph with multiple inheritance

- Good, because a type can directly be two things.
- Bad, because attribute and constraint definitions can conflict (the diamond problem).

### RDF triples or quads

- Good, because of open-world data and standard vocabularies.
- Bad, because it is awkward for transactional, constrained enterprise records.

### Datoms

- Good, because time and audit fit naturally, attribute by attribute.
- Bad, because it is unfamiliar to most developers and conflicts with versioning whole entities ([ADR-0009](0009-version-whole-entities-rather-than-single-attributes.md)).

### Documents with references

- Good, because it is simple and aggregate-oriented.
- Bad, because relationships become second-class, without their own history.

## More Information

- Prior art: TypeDB, Gel (formerly EdgeDB), Legend (PURE), Palantir's Ontology and ISO GQL. Links are in the research note.
- [ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md) decides how types are written.
