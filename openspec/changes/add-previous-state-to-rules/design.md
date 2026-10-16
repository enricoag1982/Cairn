# Design

## Context

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

The base class of a rule gains `protected Optional<T> previous(T version, RuleContext context)`, where `T` is the base class's type:

- It returns the entity or relationship with the version's ID, as committed before the transaction, at the start of the version's period, read as `T`.
- It returns empty if the store held nothing there, for example when the transaction creates it.

### D4. The test-only rule and the HR example

**The reference domain** adds the test-only rule `lei-kept` version 1, for Organization:

- An organization that had an LEI before the transaction, at the start of the checked version, still has one, with the code `lei-removed`.
- The reference domain page lists it.

**The HR example** adds the rule `decided-requests-stay` version 1, for LeaveRequest:

- A request whose status before the transaction, at the start of the checked version, was `approved` or `rejected` keeps its kind, its first and last days and its days. Its status may change only to `cancelled`.
- The code is `decided`.
- The HR schema document names the rule; the page lists it in the rule table.

**The story HR-16** goes on the HR page:

- Recorded 9 March: moving Dev's approved vacation (HR-5) to 23–27 March is rejected (`decided-requests-stay/decided`).
- It is replayed in recording order, after HR-8.
