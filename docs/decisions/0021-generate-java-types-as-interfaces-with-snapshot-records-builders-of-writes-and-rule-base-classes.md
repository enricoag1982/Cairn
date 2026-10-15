---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Generate Java types as interfaces with snapshot records, builders of writes and rule base classes

## Context and Problem Statement

Types are defined in schema documents, and Java types are generated from them by a Gradle plugin: value types, builders, a typed query model, and base types for the rules the documents name ([ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)).
Applications and rules will be written against the generated types, so their shape is public API: changing it later breaks every application.
It has to fit single inheritance with traits ([ADR-0010](0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md)), values read at a view's coordinates rather than managed objects ([ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md)), and types that agents add at runtime, which the generated code does not know ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)).
The change `add-java-type-generation` raised the question.

## Decision Drivers

- Typed access to attributes, and a Java type for every supertype and trait, so that code and rules can work on Party as well as on Person
- Values, not managed objects: immutable, no lazy loading, every change through a transaction
- Code keeps compiling when a document or an agent adds a subtype, an attribute or an allowed value
- One place validates data: the engine at commit
- Generated code that depends on `cairn-api` only, and compiles without warnings whatever the names in the document

## Considered Options

- An interface per type, with a nested snapshot record and builders that produce writes
- A record per concrete type, without interfaces
- Mutable classes that mirror the type hierarchy
- Sealed interfaces per type hierarchy

## Decision Outcome

Chosen option: "An interface per type, with a nested snapshot record and builders that produce writes", because it is the only option that gives every supertype and trait a Java type, keeps values immutable, and lets subtypes be added without breaking code.

- Every entity type, trait and relationship type becomes a public interface, with an accessor per attribute it declares. It extends the interfaces of its supertype and its traits, and `TypedEntity` or `TypedRelationship` of `cairn-api`, which give the ID and the generic state the value was read from (with a relationship's source and target).
- Required attributes have plain Java types and optional ones `Optional`: `String`, `boolean`, `int`, `long`, `BigDecimal`, `LocalDate` and `Instant` for the value types of Cairn.
- Every entity and relationship type has a nested record, `Snapshot`, with the generic state and every attribute of the type. `T.from(state)` reads a state as `T`: as the most specific generated type that its type is or extends, and as `T` itself for a type that the generated code does not know, such as a subtype that an agent registered later. A state of a known type that is not `T` or one of its subtypes is a programming error.
- The interfaces are not sealed.
- Writes are built by builders: `T.create()`, `T.patch(ref)` and `T.replace(ref)` for entity types, and `R.relate(source, target)`, `R.patch(ref)` and `R.replace(ref)` for relationship types. They produce the writes of the generic API. Builders do not check values; the engine checks them at commit, as for every write.
- A rule that a document names gets an abstract base class per name and version, such as `LeiChecksumRuleV1`. It implements `EntityRule` or `RelationshipRule`, holds the rule's name, version and type, and passes each version to a typed `check`. The description and the problem codes stay in the subclass ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)).
- Generated code keeps Cairn's names. A name that is a Java keyword, or the name of a member that generated code adds itself, gets the suffix `_`. Allowed values stay strings, because adding an allowed value is a compatible change. Deprecated types and attributes are `@Deprecated`.

### Consequences

- Good, because code and rules can work on any level of the hierarchy, and pattern matching tells the types apart.
- Good, because values are immutable records, and the only way to change data is a transaction built by builders or by the generic API.
- Good, because adding a subtype, an optional attribute or an allowed value does not break code compiled against older generated types.
- Good, because the generated code adds no validation of its own, so what is valid is decided in one place.
- Bad, because switches over the types are never exhaustive, and need a default branch.
- Bad, because each type has an interface and a nested record, which is more code to read than one class per type.
- Bad, because `T.from(state)` cannot see the supertypes of a type it does not know: a subtype registered later reads as the type the caller asked for, not as its nearest known supertype. Typed queries, which read through a view and its schema, can do better.

### Confirmation

- Tests generate the reference domain's types, compile them with all lint warnings as errors, and use them to commit and read the reference stories.
- Tests generate code from documents with awkward names, such as Java keywords and names of Java library types, and compile it.

## Pros and Cons of the Options

### A record per concrete type, without interfaces

- Good, because it is the least code, and records give equality and immutability.
- Bad, because abstract types and traits have no Java type, so a rule on Party, or code that handles any party, falls back to the generic API.
- Bad, because records cannot extend each other, so a subtype shares nothing with its supertype.

### Mutable classes that mirror the type hierarchy

- Good, because Java developers know the style from ORMs, and classes can extend each other.
- Bad, because mutable objects invite changes outside transactions, which ADR-0012 rules out: generated types are values, not managed objects.
- Bad, because traits, which a type can include several of, do not fit single class inheritance.

### Sealed interfaces per type hierarchy

- Good, because switches over a hierarchy are exhaustive, and the compiler finds missing cases.
- Bad, because a subtype added to a document, or registered by an agent at runtime, does not fit a sealed hierarchy: generated code would have to be regenerated, and applications changed, for a compatible schema change.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-java-type-generation`.
- The typed query model of ADR-0012 comes with `add-queries-and-aggregation`, where typed reads can return the nearest known supertype.
