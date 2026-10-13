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
- `View` gains `relationship(RelationshipId)`, `relationshipTimeline(RelationshipId)`, `navigateTimeline(EntityId from, String name)` returning every version of the relationships `navigate` would find at any effective time (which rules such as total ownership need to find changes later in a version's period), `navigate(EntityId from, String name)` returning `List<Link>`, and `traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth)` returning `List<Step>`.
- `Link(RelationshipState relationship, EntityState entity)`: the relationship and the entity at the other end.
- `Step(EntityState entity, int depth, RelationshipState via)`, in breadth-first order.
- `RelationshipRule extends Rule` with `void check(RelationshipState version, RuleContext context)`.

### D2. Storage

- Relationships are kept like entities: a type identifier, the two end IDs, and a map from basis to timeline. A relationship's lifetime is the union of its segments.
- Navigation scans the relationships of the named type and its subtypes whose end matches, at the view's basis and effective time. For a symmetric type, both ends match the type's name. ADR-0010's reverse index comes with the key-value store of ADR-0011; until then, scans cost the same in both directions.
- `ENTITY_EXISTS`, `INVALID_ENTITY_ID` and `ENTITY_NOT_EFFECTIVE` keep their names for relationships too: problem codes are a stable contract (ADR-0017), and their explanations say "entity or relationship".

### D3. Integrity checks, after the writes are applied

- **Ends:** each relationship's source and target are entities of the right types, checked when it is related, with the other write checks; a relationship that fails them takes no part in the checks below. Each touched relationship's ends exist at every point of its lifetime (`END_NOT_EFFECTIVE`, one problem per relationship naming the ends; a relationship whose lifetime did not grow and outlives an ended end is `RELATIONSHIP_OUTLIVES_END` instead).
- **Lifetimes:** for each entity whose lifetime shrank, every relationship with that entity as an end lies within the new lifetime.
- **Multiplicities:** for every entity that is an end of a touched relationship, or is touched itself, and for every relationship type whose source or target type is the entity's type or a supertype, the count of active relationships is computed on each interval between consecutive boundaries (relationship starts and ends, and the entity's own) and compared with the minimum and maximum. A minimum of 1 is checked only within the entity's lifetime.
- **Duplicates:** two relationships of the same type with the same ends (in either order for symmetric types) must not overlap; the later one gets the problem.
- Entities that are only ends of touched relationships take part in the lifetime and multiplicity checks, but are not validated again, do not run rules and are not recorded.
- IDs of entities and relationships share one namespace: `ENTITY_EXISTS` and `INVALID_ENTITY_ID` cover both kinds, and an entity ID given where a relationship is expected (or the reverse) is `UNKNOWN_ENTITY`, `UNKNOWN_RELATIONSHIP` or `WRONG_END_TYPE`.
- New problem codes: `UNKNOWN_RELATIONSHIP_TYPE`, `WRONG_END_TYPE`, `END_NOT_EFFECTIVE`, `RELATIONSHIP_OUTLIVES_END`, `TOO_FEW_RELATIONSHIPS`, `TOO_MANY_RELATIONSHIPS`, `DUPLICATE_RELATIONSHIP` and `UNKNOWN_RELATIONSHIP`. Paths name the relationship (`$owns1`, its ID, or `@write.<n>` when it has neither), or, for multiplicities, the entity followed by the relationship type's or inverse name in brackets (`$a3[HeldBy]`), so they cannot be mistaken for attribute paths.

### D4. Composition cascades

- After all writes are applied and before the integrity checks, for each entity that a write ends, the engine ends from the same instant every composition relationship whose source is that entity and that continues past the instant, and their targets, recursively. Compositions created in the same transaction are covered too. `Receipt` gains `ended`, the IDs of the relationships and entities that cascades ended, so callers see them: for each entity the writes ended, in write order, each composition relationship by ID, then its target, then that target's compositions, depth first; each ID once.
- A target that a cascade ends must not have relationships of its own that continue; the lifetime check reports them.

### D5. Rules on relationships

- Effective rules of a relationship type include those of its relationship supertypes.
- `RuleRunner` and `Preparation` generalize from entities to subjects (entities and relationships): `RuleRunner.Subject` gains relationship types, the check that a rule fits its type works in both directions (an `EntityRule` named by a relationship type, or a `RelationshipRule` named by an entity type, is `RULE_FAILED`), and the explanations of `RULE_VIOLATION` and `RULE_FAILED` and the `RuleContext.problem` Javadoc stop saying "entity".
- Rules are skipped for a subject that already has a declarative problem. The matching by path learns relationship paths and the multiplicity paths of D3 (`$a3[HeldBy]`), which concern the entity `$a3`.
- `total-ownership-at-most-100` collects, with `navigateTimeline`, every instant within the version's period where an `Owns` relationship to the same target starts or ends, and checks the sum at each with `viewAt`. It reports the first offending instant of each checked version.
- The rule moves from a declared rule to an implementation in the test fixtures: `ReferenceModel.executableRules()` gains `TotalOwnershipRule`, `ReferenceModel.declaredRules()` becomes empty, and `ReferenceStore.referenceSettings()` follows.

### D6. Reference domain

- Story RD-13 is added to the reference domain page, which states its arithmetic.
- `ReferenceStories` gains the relationship stories RD-2 to RD-4, RD-8 and RD-13, and RD-5 relates accounts to their holders, as the multiplicity of `HeldBy` requires (its builder then takes the holders' references).
- With `HeldBy` exactly one, an account can no longer be created alone. The tests of `add-entity-versions` that create accounts alone (through `ReferenceStore.createAccounts()`, the value, replace and conflict scenarios) are updated to create the holder too, keeping what each test checks.

## Risks / Trade-offs

- [Multiplicity checks scan relationships for every touched entity] → Acceptable in memory; indexes come with the storage interface.
- [Cascades add writes the caller did not list] → They follow from the schema's composition rule, and the receipt lists every ended relationship and entity.
- [Required relationships make some single writes impossible, such as creating an account alone] → Intended: the account and its holder are created in one transaction.
