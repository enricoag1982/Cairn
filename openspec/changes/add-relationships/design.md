# Design

## Context

Entities, transactions, views and entity rules exist (`add-entity-versions`, `add-entity-rules`, which must be archived first). The meta-model already has relationship types with source and target types, two multiplicities, an inverse name, composition or association, a symmetric mark and attributes (`type-model`). This change stores relationships. The requirements are in `specs/relationships/spec.md` and `specs/entity-rules/spec.md`.

## Goals / Non-Goals

**Goals:**

- Relationships versioned exactly like entities, so that writes, corrections, views and rules work the same way.
- Integrity checked at commit, at every point in effective time, over the state the transaction would produce.

**Non-Goals:**

- Aggregation and query planning: `add-queries-and-aggregation`.
- Indexes: the in-memory store scans.

## Decisions

### D1. API

- `RelationshipId(String value)` joins `Id`. Entities and relationships share the store's one ID generator (ADR-0019), so `IdGenerator.next()` changes to return the ID's text, which the store wraps as an `EntityId` or a `RelationshipId`. Supplied IDs must be unique across entities and relationships.
- A new write, `Relate(Optional<TemporaryId> temporaryId, Optional<RelationshipId> id, String relationshipType, Ref source, Ref target, EffectivePeriod period, Map<String, Value> values)`, offered by the transaction builder as `relate`. `Patch`, `Replace` and `EndDate` already take any `Ref`.
- `RelationshipState(RelationshipId id, String relationshipType, EntityId source, EntityId target, Map<String, Value> values, EffectivePeriod period, Instant recordedAt, Basis recordedBasis)`.
- `View` gains `relationship(RelationshipId)`, `relationshipTimeline(RelationshipId)`, `navigate(EntityId from, String name)` returning `List<Link>`, and `traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth)` returning `List<Step>`.
- `Link(RelationshipState relationship, EntityState entity)`: the relationship and the entity at the other end.
- `Step(EntityState entity, int depth, RelationshipState via)`, in breadth-first order.
- `RelationshipRule extends Rule` with `void check(RelationshipState version, RuleContext context)`.

### D2. Storage

- Relationships are kept like entities: a type identifier, the two end IDs, and a map from basis to timeline. A relationship's lifetime is the union of its segments.
- Navigation scans the relationships of the named type and its subtypes whose end matches, at the view's basis and effective time. For a symmetric type, both ends match the type's name.

### D3. Integrity checks, after the writes are applied

- **Ends:** each touched relationship's source and target are entities of the right types, and exist at every point of the relationship's lifetime.
- **Lifetimes:** for each entity whose lifetime shrank, every relationship with that entity as an end lies within the new lifetime.
- **Multiplicities:** for every entity that is an end of a touched relationship, or is touched itself, and for every relationship type whose source or target type is the entity's type or a supertype, the count of active relationships is computed on each interval between consecutive boundaries (relationship starts and ends, and the entity's own) and compared with the minimum and maximum. A minimum of 1 is checked only within the entity's lifetime.
- **Duplicates:** two relationships of the same type with the same ends (in either order for symmetric types) must not overlap.
- New problem codes: `UNKNOWN_RELATIONSHIP_TYPE`, `WRONG_END_TYPE`, `END_NOT_EFFECTIVE`, `RELATIONSHIP_OUTLIVES_END`, `TOO_FEW_RELATIONSHIPS`, `TOO_MANY_RELATIONSHIPS`, `DUPLICATE_RELATIONSHIP` and `UNKNOWN_RELATIONSHIP`. Paths name the relationship (`$owns1`, its ID, or `@write.<n>` when it has neither), or, for multiplicities, the entity followed by the relationship type's or inverse name in brackets (`$a3[HeldBy]`), so they cannot be mistaken for attribute paths.

### D4. Composition cascades

- Before the integrity checks, for each entity that a write ends, the engine adds end-date writes from the same instant for every composition relationship whose source is that entity, and for their targets, recursively. The added writes are part of the transaction and appear in its effects.
- A target that a cascade ends must not have relationships of its own that continue; the lifetime check reports them.

### D5. Rules on relationships

- Effective rules of a relationship type include those of its relationship supertypes.
- `RuleContext.viewAt` lets `total-ownership-at-most-100` collect every boundary within the version's period where an `Owns` relationship to the same target starts or ends, and check the sum at each.
- The rule moves from a descriptor to an implementation in the test fixtures.

### D6. Reference domain

- Story RD-13 is added to the reference domain page: recorded 1 April, Gamma is created and related to Beta with Owns, share 50, from 1 April; it is rejected, because with ACME's 55% the owners would hold 105%.
- `ReferenceStories` gains the relationship stories RD-2 to RD-4, RD-8 and RD-13, and RD-5 relates accounts to their holders, as the multiplicity of `HeldBy` requires (its builder then takes the holders' references).
- With `HeldBy` exactly one, an account can no longer be created alone. The tests of `add-entity-versions` that create accounts alone (through `ReferenceStore.createAccounts()`, the value, replace and conflict scenarios) are updated to create the holder too, keeping what each test checks.

## Risks / Trade-offs

- [Multiplicity checks scan relationships for every touched entity] → Acceptable in memory; indexes come with the storage interface.
- [Cascades add writes the caller did not list] → They follow from the schema's composition rule, and the receipt lists every ended relationship and entity.
- [Required relationships make some single writes impossible, such as creating an account alone] → Intended: the account and its holder are created in one transaction.
