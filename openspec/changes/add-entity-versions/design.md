# Design

## Context

The schema API exists (`type-model`, `schema-evolution`, extended by `add-self-describing-schema` and `add-schema-documents`, which must be archived first). This change stores entities. The requirements are in `specs/transactions`, `specs/entity-versions`, `specs/views` and `specs/schema-evolution`.

Constraints that shape the approach:

- ADR-0007: one writer orders all commits; readers work on immutable snapshots and never wait.
- ADR-0008: effective time and transaction time, UTC instants with microsecond precision, half-open periods, a basis per commit; nothing recorded is changed.
- ADR-0009: one version per entity and effective period; stored values carry attribute identifiers; conflict checks per entity or per attribute.
- ADR-0013: reads go through immutable views.
- ADR-0017: dry runs, idempotency keys, principal, party acted for and reason.
- ADR-0019: opaque IDs, one generator per store, UUIDv7 by default, temporary and supplied IDs.
- ADR-0020: values are checked against patterns with the I-Regexp matcher that `cairn-core` already has.
- ADR-0011 (storage behind an ordered key-value store and a commit log) is not built here. The in-memory store is shaped so the engine logic can later move onto that interface.

## Goals / Non-Goals

**Goals:**

- A small public API for stores, transactions, writes and views in `cairn-api`, implemented in memory in `cairn-core`.
- Validation of the whole resulting state of every entity a transaction touches, with problems agents can act on.
- One sequence of bases and transaction times for schema and data.

**Non-Goals:**

- Rules at commit, JSON payloads, relationships: the next changes.
- Performance work: the in-memory store scans where an index would be faster. Schemas are small and test data sets are small.
- Recording a principal for schema transactions: `add-agent-tools`, with the approval policy.

## Decisions

### D1. `Literal` becomes `Value`

- `Literal` and its records become `Value`, `StringValue`, `BooleanValue`, `IntValue`, `LongValue`, `DecimalValue`, `DateValue` and `InstantValue`; `Literal.of` becomes `Value.of`. Range bounds and examples hold values.
- Done first, as its own commit, so that the behavior changes are reviewed separately.

### D2. The public API, package `io.github.enricoag1982.cairn.data`

- `Store`: `schema()` (its `SchemaRegistry`), `commit(Transaction)`, `dryRun(Transaction)`, `view(Coordinates)`, `latestBasis()`, and `transactions()` (the history of data transactions with who and why).
- `Stores.inMemory()` and `Stores.inMemory(StoreSettings)` in `cairn-core`, package `io.github.enricoag1982.cairn.core`. `StoreSettings` holds the clock (`InstantSource`), the ID generator and the available rules as `RuleDescriptor`s (passed on to the schema registry; `add-entity-rules` adds executable rules), built through a builder with defaults. `cairn-api`'s module exports the new package.
- `Transaction(String principal, Optional<String> onBehalfOf, Optional<String> reason, Optional<String> idempotencyKey, List<Write> writes)`, built with `Transaction.by(principal)`, which offers `onBehalfOf`, `reason`, `idempotencyKey`, `create`, `patch`, `replace` and `endDate`.
- `Write` is sealed:
  - `Create(Optional<TemporaryId> temporaryId, Optional<EntityId> id, String entityType, EffectivePeriod period, Map<String, Value> values)`
  - `Patch(Ref target, EffectivePeriod period, Map<String, Value> set, Set<String> clear, ConflictCheck check)`
  - `Replace(Ref target, EffectivePeriod period, Map<String, Value> values, ConflictCheck check)`
  - `EndDate(Ref target, Instant from, ConflictCheck check)`
- `Ref` is sealed: an `Id` or a `TemporaryId(String name)`. Writes that patch, replace or end-date take any `Ref`.
- `EffectivePeriod(Instant from, Optional<Instant> until)`, truncated to microseconds by its constructor; `EffectivePeriod.from(Instant)` and `between(Instant, Instant)`.
- `ConflictCheck` is sealed: `none()`, `perEntity(Basis readAt)`, `perAttribute(Basis readAt)`.
- `CommitResult` is sealed: `Committed(Receipt receipt)` or `Rejected(List<Problem> problems)`. `DryRunResult` is sealed: `WouldCommit()` or `Rejected(List<Problem> problems)`.
- `Id` is sealed and extends `Ref`: `EntityId(String value)` now, and `RelationshipId` with `add-relationships`.
- `Receipt(Basis basis, Instant transactionTime, Map<TemporaryId, Id> temporaryIds, List<Id> created, String principal, Optional<String> onBehalfOf, Optional<String> reason, Optional<String> idempotencyKey)`.
- `TransactionRecord(Basis basis, Instant transactionTime, String principal, Optional<String> onBehalfOf, Optional<String> reason)`.
- `Coordinates`: an effective instant and a sealed `KnownAt` (`latest()`, `basis(Basis)`, `transactionTime(Instant)`), built with `Coordinates.effectiveAt(instant)`, optionally `.knownAt(...)`.
- `View`: `effectiveAt()`, `basis()`, `schema()`, `entity(EntityId)` returning `Optional<EntityState>`, `entities(String entityType)` returning `List<EntityState>`, and `timeline(EntityId)` returning `List<EntityState>`.
- `EntityState(EntityId id, String entityType, Map<String, Value> values, EffectivePeriod period, Instant recordedAt, Basis recordedBasis)`, with values in a map ordered by the attributes' order in the schema.
- `IdGenerator` (an interface: `next()`, `accepts(String)` and `copy()`, an independent copy in the same state), with `IdGenerators.uuidV7()`, `uuidV7(InstantSource, RandomGenerator)` and `deterministic()` in `cairn-core`.

### D3. One writer and one sequence

- The store owns a `CommitSequence` (internal package `io.github.enricoag1982.cairn.core.commit`): under its writer lock, `next()` proposes the next basis and transaction time (the time strictly increasing and truncated to microseconds) without advancing, and `publish(...)` advances the sequence and the latest basis once the commit's data is in place. A dry run or a rejected commit therefore uses no basis.
- The store's schema registry is an `InMemorySchemaRegistry` built on the store's sequence and lock. `SchemaRegistries.inMemory(...)` keeps creating stand-alone registries with their own sequence.
- One lock serializes schema and data commits. The latest committed basis is published through a volatile field after a commit's data is in place; views read only data at or below their basis, so they never wait and never see a partial commit.

### D4. In-memory representation: a timeline per entity and basis

- For every entity the store keeps its type identifier and a map from basis to timeline. A timeline is the entity's complete effective history as known after that basis: an immutable, sorted list of segments, each an effective period with values keyed by `AttributeId`.
- A commit computes new timelines for the touched entities from their latest ones and appends them under the new basis. Nothing is changed in place.
- A view at basis B takes, for each entity, the timeline with the greatest basis at or below B, then the segment that contains its effective time. A view known at a transaction time first finds the greatest basis recorded at or before that time.
- Adjacent segments with equal values are merged, so that a timeline lists real changes only.
- Alternative: versions with a transaction-time end that a later commit sets. Rejected for the in-memory store, because setting the end changes recorded data and needs care for concurrent readers; timelines per basis are append-only and trivially safe.

### D5. Applying writes

- Writes are applied in order to working copies of the timelines. A create starts a timeline; a patch splits the segments at the period's bounds and sets or clears values in the overlap; a replace sets the overlap's values to exactly the given ones; an end-date cuts every segment at the instant.
- A patch or replace whose period is not covered by the entity's lifetime (the union of its segments) is `ENTITY_NOT_EFFECTIVE`. Writes never extend a lifetime; re-opening an ended entity is a later concern.
- Values are converted to the attribute's value type when written (an int written to a long attribute is stored as a long). When read, a value is converted to the attribute's value type in the view's schema, so widened attributes read in their new type.

### D6. Validation

- After all writes are applied, every segment of every touched entity is checked: known attributes, value types, ranges, string constraints and required attributes. Deprecated attributes are checked on the writes themselves, not on stored values.
- Unique attributes are checked for the touched entities against every other entity whose type has the same `AttributeId`, comparing overlapping segments. With scans, the cost is linear in the number of entities, which is acceptable for the in-memory store.
- Conflict checks compare the entity's timelines at the caller's basis and at the latest basis: per entity, any difference rejects; per attribute, only differences in the attributes the write sets or clears.
- New problem codes, each with an explanation: `UNKNOWN_ENTITY_TYPE`, `ABSTRACT_ENTITY_TYPE`, `UNKNOWN_ATTRIBUTE`, `VALUE_TYPE_MISMATCH`, `VALUE_OUT_OF_RANGE`, `VALUE_NOT_ALLOWED`, `VALUE_PATTERN_MISMATCH`, `VALUE_LENGTH`, `MISSING_REQUIRED_VALUE`, `DEPRECATED_ATTRIBUTE`, `UNIQUE_VALUE_TAKEN`, `UNKNOWN_ENTITY`, `ENTITY_NOT_EFFECTIVE`, `ENTITY_EXISTS`, `INVALID_ENTITY_ID`, `UNKNOWN_TEMPORARY_ID`, `DUPLICATE_TEMPORARY_ID`, `INVALID_PERIOD`, `CONFLICT`, `MISSING_PRINCIPAL` and `IDEMPOTENCY_KEY_REUSED`.
- Paths: an entity is `$` and its temporary ID, or its ID; an attribute is appended with a dot (`$acme.name`, `0199...e1.balance`); problems of the transaction itself use `@transaction`. Problems about a value fill in expected and found; unknown names get the closest-name hint.

### D7. Dry runs and idempotency

- `dryRun` runs the same code as `commit` under the writer lock, up to the point of appending, and appends nothing. IDs for new entities are drawn from `copy()` of the store's generator; a commit keeps the copy as the store's generator, and a dry run or a rejected commit throws it away, so no ID is reserved. A seeded UUIDv7 generator's copy shares its random source, so a dry run shifts later random bits but not their order.
- The store keeps every committed idempotency key with its transaction and receipt. A transaction equal to the stored one (record equality) gets the stored receipt; any other with the same key is `IDEMPOTENCY_KEY_REUSED`.

### D8. ID generators

- UUIDv7 follows RFC 9562: 48 bits of Unix milliseconds from the clock, then random bits, with a counter in `rand_a` keeping IDs ordered within a millisecond.
- The deterministic generator produces UUIDv7-shaped IDs from a counter and timestamp zero, so tests see the same format as production.
- `accepts` checks the text form: lowercase, hyphenated, version 7, variant `10`.

### D9. Tests and the reference stories

- The test fixtures gain `ReferenceStories`, which builds the transactions of RD-1 to RD-10 (those without relationships) with fixed recording dates, using a settable test clock that moves to the fixtures.
- One test per scenario, named `Scenario: <name>`, in one test class per capability.

## Risks / Trade-offs

- [Timelines per basis copy an entity's whole history on every write] → Acceptable in memory for the reference implementation; the key-value design of ADR-0011 stores versions, not timelines.
- [Uniqueness by scanning] → Indexes come with the storage interface; the behavior is fixed by the specs now.
- [Schema transactions record no principal yet] → Added with the approval policy in `add-agent-tools`.
- [The API surface is large for one change] → It follows the ADRs closely, and each part is covered by scenarios.
