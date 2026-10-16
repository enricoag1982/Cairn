# Design

## Context

ADR-0032 decides this change: rules read the store as committed before the transaction, with recorded reads, and "before" in a re-check is the state before the re-checking transaction.

Rules run in `RuleStage` against a provisional view of the state the transaction would produce, through `RuleContext.view()` (at the start of the version's period) and `viewAt(Instant)`. Every read goes through views that record what they read (ADR-0025). The committed store is at the latest basis the commit sequence published; the transaction is not yet applied to it.

## Goals / Non-Goals

**Goals:** let rules read the committed state before the transaction, with the same recording of reads, and give generated base classes a typed previous version.

**Non-goals:** see the proposal.

## Decisions

### D1. The API

`RuleContext` gains two methods:

- `View before()`: the committed store at the latest basis before the transaction, effective at the start of the checked version's period.
- `View beforeAt(Instant effectiveAt)`.

Both views are read-only and immutable. Their `basis()` is the latest committed basis, and their `schema()` is the schema at that basis. A rule that runs while the transaction registers no schema sees the same schema in both.

### D2. Reads, re-checks and dry runs

Reads through `before` views are recorded as other reads are, with the same keys. Recording more than is needed only causes re-checks, which give the same verdict, so it is safe.

In a re-check, the transaction that runs the check again is the one whose "before" the rule reads. For a version that the transaction does not change, `before()` reads it as it is.

Dry runs read the committed state as commits do. Learning reads after recovery runs rules against the committed state as both "now" and "before".

### D3. Generated base classes

The base class of a rule gains `protected List<T> previous(T version, RuleContext context)`, where `T` is the base class's type:

- It returns the versions of the entity or relationship with the version's ID that the store held before the transaction during the checked version's period, in order, read as `T`. It reads them from `context.before()`'s timeline, which is recorded as a timeline read.
- It returns an empty list when the store held nothing in that period, for example when the transaction creates it.
- A rule's verdict must hold at each instant of the version's period (`entity-rules`), so a rule compares with every previous version, not only with the one at the start.
- Reading the timeline costs re-checks: every later change to the entity's timeline runs the rule again for its other versions that read it. A re-check reads "before" as the state before the re-checking transaction, which gives the same verdict for versions that transaction does not change, so it costs time, not correctness.

### D4. The test-only rule and the HR example

**The reference domain** adds the test-only rule `lei-kept` version 1, for Organization:

- An organization that had an LEI before the transaction, at any time in the checked version's period, still has one, with the code `lei-removed`.
- The reference domain page lists it.

**The HR example** adds the rule `decided-requests-stay` version 1, for LeaveRequest:

- A request whose status before the transaction, at any time in the checked version's period, was `approved` or `rejected` keeps the kind, first and last days and days it had then. Its status may change only to `cancelled`.
- The code is `decided`.
- The HR schema document names the rule; the page lists it in the rule table.

**The story HR-16** goes on the HR page:

- Recorded 9 March: moving Dev's approved vacation (HR-5) to 23–27 March is rejected (`decided-requests-stay/decided`).
- It is replayed in recording order, after HR-8.
