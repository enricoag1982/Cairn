# Design

## Context

Rules run in the writer thread at commit, for the changed segments of the entities and relationships a transaction writes (`RuleStage`, `RuleRunner`), and read the state the transaction would produce through `ProvisionalView`. Nothing remembers what a rule read, so a write elsewhere cannot run it again. ADR-0025 decides that the store records the reads of every check and runs a rule again when a transaction changes what it read. It refines ADR-0018 and adds a step after the recovery of ADR-0022; ADR-0007 (one writer) makes the index of reads writer-only state.

## Goals / Non-Goals

**Goals:** close the gap of the HR domain page for every rule, without changes to the rule API; the same verdicts with every storage implementation; costs proportional to what a transaction changes and what the affected checks read.

**Non-goals:** see the proposal. Reads are not kept in files, schema registrations and purges run no rules, and rules without code cannot be protected from other writes.

## Decisions

### D1. What a read is

A read is a key and an effective time, or a key across effective time. The keys:

| Key | Recorded by | Across effective time by |
| --- | --- | --- |
| Entity `E(id)` | `entity`, every `EntityState` a read returns | `timeline` |
| Relationship `R(id)` | `relationship`, every `RelationshipState` a read returns | `relationshipTimeline`, every version `navigateTimeline` returns |
| Navigation `N(entity, relationship type, direction)` | `navigate`, each step of `traverse`, each follow step of a query | `navigateTimeline` |
| Type `T(entity type)` | `entities`, the start of a query | none |

The relationship type of a navigation key is the type that the name resolves to, and the key covers its subtypes, as navigation does; a symmetric type records both directions. A type key covers subtypes the same way. `schema()`, `basis()` and `effectiveAt()` are not reads.
Reads are recorded in `ProvisionalView`, below the `View` methods that `NavigableView`, `QueryRunner` and traversals build on, so that every way a rule reads is covered. Each `RuleContext` gets views that record into the check it belongs to; views made for other purposes record nothing.

*Alternative:* recording at the `View` interface with a wrapper. Rejected: `NavigableView.query` and `traverse` call the view's own methods, which a wrapper does not see.

### D2. What a transaction changes

From the before and after timelines of each entity and relationship it writes, a transaction yields *changes*: a key and the effective periods in which it differs.

- An entity whose values or existence differ in a period changes `E(id)` there; where its existence differs, it also changes `T` of its type and each supertype.
- A relationship whose values or existence differ changes `R(id)`; where its existence differs, it also changes `N(source, type, forward)` and `N(target, type, inverse)` for its type and each supertype, and both directions at both ends for a symmetric type.

A read at an effective time is hit by a change whose periods contain that time; a read across effective time by any change of its key. Splitting a version without changing values is no change. Over-approximating is safe for data that meets its rules, but would make results differ between implementations where data does not, so the granularity above is the specification, and tests pin it.

### D3. The index of reads

The store keeps, for each entity or relationship and each rule with code that its type names, a list of *checks*: the effective period that one run of the rule covered, which later writes can cut into several parts, and its reads. An inverse map leads from each key to the checks that read it. The index is writer-only state, beside the latest timelines; views and readers never see it.

- A run of a rule for a version records a check for that version's period, replacing the parts of older checks of the same entity or relationship and rule that it overlaps.
- A write that changes or ends part of a timeline removes that part from the periods of older checks; a check whose period becomes empty is dropped.
- A check whose rule its type no longer names, after a schema registration, is dropped when a change reaches it.
- A purge drops the checks of what it removes, and the keys of removed entities and relationships from other checks, so that no purged ID stays (ADR-0024).
- The checks of a transaction are staged and applied only when it commits; a dry run or a rejected transaction leaves the index as it was.

### D4. Running re-checks

After `RuleStage` has run the rules of what the transaction writes, and only if the transaction has no problems other than from rules, the store collects the changes of D2, looks up the checks they hit, and runs each rule again for the versions of the entity or relationship that overlap the hit check's period, against the same provisional view. Versions that `RuleStage` already ran for the same rule are not run twice.

- Rules that reported a problem, of any kind, about something the transaction writes are left out, so a sum rule such as `total-ownership-at-most-100` reports once (scenario "One problem from a rule").
- Re-checks run in the order of the IDs of the entities and relationships, then of the type's rules, then of the versions. Their problems come after all others (`transactions`).
- A problem has the path of the entity or relationship as the transaction names it, which is its ID when it does not, and the hint "The transaction changes what the rule read when it last checked <path>." A rule's own problems have no hint today, so the hint tells them apart.
- Re-checks record reads like any run (D3), and never cause more re-checks: rules do not write.

### D5. Durable stores

Recovery stays as ADR-0022 has it. Afterwards, before the store is returned, it runs every rule it has code for over every version of every entity and relationship whose type names it, against a provisional view with no transaction at the latest basis, and records the checks. What the rules report, throw or try to write is dropped. The index is not written to files, so the log and snapshot formats do not change.

### D6. The HR example

The HR-5 test asserts that ending Cleo's `ManagedBy` of `PLT` from 10 March is rejected with `decided-by-manager/not-a-manager` about her decision, whose path is its ID. If the generated company of HR-14 now has writes that re-checks reject, the generator changes so that its data meets its rules, and the page says how. The gap leaves the HR domain page.

## Risks / Trade-offs

- **Memory:** the index holds every read of every version's last check. The HR and reference fixtures are small; the risk grows with stores, and a later change can keep reads in files (ADR-0025).
- **Opening a durable store** runs every rule once. Tests measure it for HR-14; it is linear in the checked versions.
- **Rules that list a type or read timelines** are hit by many changes. The guide and the javadoc tell rule authors to navigate from what they check.
- **Data that broke a rule before** this change rejects writes that reach it. Stores of this branch were never released, so none exists outside tests.
