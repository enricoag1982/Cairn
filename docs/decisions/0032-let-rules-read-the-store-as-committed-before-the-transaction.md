---
status: accepted
date: 2026-10-16
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Let rules read the store as committed before the transaction

## Context and Problem Statement

A rule reads other data through its rule context "at the same coordinates as the data being checked" ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)): the state the transaction would produce. It cannot see what the store held before, so no rule can forbid a change to what already holds, such as a decided leave request in the [HR domain](../reference/hr-domain.md), or an LEI that must not be removed once given.
What a rule may read, and what "before" means when a rule runs again ([ADR-0025](0025-run-a-rule-again-when-the-data-it-read-changes.md)), become part of every rule's contract and of the generated base classes ([ADR-0021](0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md)), so they are costly to change. The change `add-previous-state-to-rules` raised it.

## Decision Drivers

- A rule's verdict holds at each instant of the checked version's period and does not depend on where that period starts (the `entity-rules` spec)
- What a rule reads is recorded, whatever it reads, so that it runs again when that changes (ADR-0025)
- The same result with every storage implementation, in commits, dry runs, re-checks and learning after recovery
- Rules stay read-only and deterministic (ADR-0018)

## Considered Options

- Views of the store as committed before the transaction that runs the rule, through the rule context, with recorded reads
- Only the previous version of the checked entity or relationship, passed to the rule
- "Before" as of the transaction that wrote the checked version, also in re-checks
- Transition rules declared in documents or in CEL

## Decision Outcome

Chosen option: "Views of the store as committed before the transaction that runs the rule", because it lets a rule compare with anything the store held, over the whole period it checks, with the same recording of reads as every other read.

- **The views.** `RuleContext.before()` and `beforeAt(Instant)` read the store at the latest committed basis, without the transaction's writes: at the start of the checked version's period, or at another effective time. This replaces, for these views, ADR-0018's sentence that a rule reads at the same coordinates as the data being checked; `view()` and `viewAt(Instant)` still read the state the transaction would produce.
- **Reads.** Reads through these views are recorded with the same keys as other reads (ADR-0025).
- **In a re-check,** "before" is the state before the transaction that runs the rule again; for a version that transaction does not change, it reads the version as it is, so the verdict stays. In dry runs it is the committed state, as for commits, and when a store learns what its rules read after recovery, both kinds of view read the committed state.
- **Generated base classes** gain `previous(version, context)`: the versions of the checked entity or relationship that the store held before the transaction during the checked version's period, as its generated type, read from the timeline, so that a rule compares with each of them. This adds to the base classes of ADR-0021.

### Consequences

- Good, because rules can forbid changes to what already holds, such as decided requests and given LEIs, at every instant of the period they check.
- Good, because every read stays recorded, so a later change to what the rule compared with runs it again.
- Bad, because a rule that reads the previous timeline runs again on every change to that timeline; the verdict is the same for versions the change does not touch, so it costs time, not correctness.
- Bad, because the meaning of "before" moves with the transaction that runs the rule, so a rule cannot ask what the store held when the version was first written; views at any coordinates outside rules can.

### Confirmation

- Scenario tests reject removing an LEI that `lei-kept` protects, accept creations, and show a re-check reading the state before the transaction that runs it.
- The HR example rejects moving an approved vacation (HR-16), including through a patch that also sets the approval.

## Pros and Cons of the Options

### Only the previous version, passed to the rule

- Good, because it is the smallest API.
- Bad, because a version's period can cover several earlier versions, and a rule that sees one of them can be fooled by where the period starts.

### "Before" as of the transaction that wrote the version

- Good, because the meaning does not change in a re-check.
- Bad, because a re-check would then compare with a state that a later transaction may have corrected, and the store would have to keep, per version, the basis that wrote it, for every check.

### Transition rules in documents or CEL

- Good, because agents could write them at runtime.
- Bad, because checks see one version only ([ADR-0030](0030-write-checks-within-one-version-in-cel-evaluated-by-an-optional-module.md)); comparing versions in documents needs more design, and can follow on this decision.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again; the previous version in the rule context was on the roadmap the user agreed to on 2026-10-15, with the proposal of `add-previous-state-to-rules`.
- It replaces one sentence of ADR-0018 for the `before` views and adds `previous` to ADR-0021's base classes; both ADRs stay in force for the rest and refer to it.
