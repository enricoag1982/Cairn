# Design

## Context

References are `Ref`s: an `Id` (entity or relationship) or a `TemporaryId`. `Workspace.resolve` turns them into working entities and relationships, and problems name stored entities by their IDs. Payloads write references as `$temporary` or an ID, and the agent read tools take `id`. ADR-0026 adds a third kind, the unique key, resolved by the store at commit. It relies on ADR-0019, ADR-0007, ADR-0022 and ADR-0025.

## Goals / Non-Goals

**Goals:** keys in Java writes and reads, in payloads, in the agent read tools and in generated types; resolution inside the writer, with clear problems; the same results with every storage implementation.

**Non-goals:** see the proposal.

## Decisions

### D1. The API

`UniqueKey(String entityType, String attribute, Value value)` in `cairn-api`'s `data` package is the third permitted `Ref`; type and attribute are names or aliases. `View.entity(UniqueKey)` finds the entity, as `View.entity(EntityId)` reads one, and throws `IllegalArgumentException` for an unknown type, an attribute that the type does not have or that is not unique, or a value that does not fit, as listing an unknown type does. The problem codes are `INVALID_UNIQUE_KEY` and `UNIQUE_KEY_NOT_FOUND`, each with an explanation.

*Alternative:* `Optional<EntityId> View.find(...)`. Rejected: callers want the state, and the ID is in it.

### D2. Resolving keys in a transaction

Before a write is applied, `Workspace` resolves each key it names at the start of the write's period: a patch, replace or end-date at its `from`, a relate's source and target at its `from`. It checks the key against the transaction's schema, then scans the committed entities of the type and its subtypes for the one whose latest timeline has the value at that instant; uniqueness over effective time makes it at most one. Then it continues as for that entity's ID. Problems have the path `@write.<position>` and name the key and, for an invalid one, the type's unique attributes. Resolving against the committed state, not the transaction's own writes, makes the result independent of the order of writes; temporary IDs name what the transaction creates.
The receipt lists each resolution, `KeyResolution(int write, UniqueKey key, EntityId entity)`, in the order of the writes, so that a caller learns which entity a key named; the same key can resolve to different entities in writes with different periods.

### D3. Views and rule reads

`NavigableView.entity(UniqueKey)` lists the entities of the type at the view's effective time and returns the one with the value. In `ProvisionalView` this records, as listing does, the type key and the entity key of each entity listed (D1 of `add-rule-rechecks`), so a rule that finds by key runs again when any entity of the type changes at the instants it read.

### D4. The log of durable stores

The transaction codec gains a tag for unique keys, with the type, the attribute and the value as values are encoded. Receipts gain their key resolutions, which log records and the snapshot frames of idempotent transactions carry as an optional trailer, so that a purge after reopening still finds them. Files keep format version 1, as `add-purge` did: no release of Cairn has written them, and records and frames without keys read as before.

### D4a. Purges

A purge decides which stored idempotent transactions wrote purged data from their receipts and writes. It counts the receipt's key resolutions too, so a transaction that named a purged entity by key is dropped like one that named it by ID, and its key, whose value may be personal data, leaves memory and, through the rewritten files, the disk (ADR-0024).

### D5. Payloads

`cairn-documents` reads a reference that is a mapping with `entityType` and a `key` of exactly one entry as a `UniqueKey`, typing the value by the attribute in the named type, or by its own YAML type if the schema does not have them. Other shapes are read problems located at the reference or at its `key`.
The reader also resolves each key while it reads, through its `IdLookup` (`IdLookup.find(UniqueKey, Instant)`, at the write's start; `IdLookup.fromStore` reads the store's latest view, as the agent tools do). A key that resolves makes the write concern that entity, so problems that name its ID are located at the writes that name it by key, and the write's values are typed by that entity's type, which may be a subtype of the key's. Without a store, or when the key names no entity, the write concerns no stored entity, values are typed by the key's type, and problems about the entity fall back as for subjects that no write names.

### D6. Agent tools

`cairn_read_entity`, `cairn_navigate` and `cairn_traverse` take `id` or `key`. Exactly one must be given, otherwise the problem has the path `@arguments.id`; a key is read as payloads read it, and an invalid key is a problem at `@arguments.key`. Their input schemas describe both.

### D7. Generated types

`cairn-codegen` adds to each entity interface a static `by<Attribute>` method per unique attribute, inherited ones included, which converts the Java value as builders do and returns a `UniqueKey` with the type's name. The names go through the same clash rules as other generated members.

### D8. The HR example

The agent of HR-13 writes its payload with keys for the unit, the location, the employer and the job, and no longer queries for their IDs. Stories that name existing entities by number or code can use keys where it reads better. The gap "No lookup by a unique attribute" leaves the HR domain page.

## Risks / Trade-offs

- **Scans:** each key scans its type. Fine for the fixtures; indexes come with the key-value store.
- **A key that moved** changes a different entity on a different date. The scenario "A key whose value moved" pins it, and problems name the ID.
- **Reading and committing are two steps:** the reader resolves keys to locate problems and type values, and the commit resolves them again. If the store changes in between, a problem is located less precisely, and a value may be typed for the entity found when reading; the commit validates against the entity it resolves, so a wrongly typed value is reported, never stored.
