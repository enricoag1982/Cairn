# Proposal

## Why

A rule sees the version it checks and reads the state the transaction would produce (ADR-0018), but not what the store held before. So no rule can say that something must not change once it holds, such as a decided leave request in the HR example, or an LEI that must not be removed once given. The HR page lists this gap: "The previous version in the rule context".

## What Changes

- `RuleContext` gains `before()` and `beforeAt(Instant)`: read-only views of the store as committed before the transaction, at the start of the checked version's period or at another effective time, with the coordinates of the latest committed basis. A rule reads its own entity's previous version with `context.before().entity(version.id())`.
- Reads through those views are recorded like other reads (ADR-0025). In a re-check, "before" is the state before the transaction that runs the check again.
- Generated rule base classes gain `previous(version, context)`, the checked entity or relationship as committed before the transaction at the start of the version's period, as its generated type.
- The HR example adds the rule `decided-requests-stay` and the story HR-16: a decided leave request keeps its dates and kind.

### Non-goals

- Transition rules declared in documents, or in CEL.
- Reading the store at bases other than the latest committed one; views at any coordinates already do that outside rules.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `entity-rules`: the state before the transaction in the rule context.
- `java-types`: `previous` in generated rule base classes.

## Impact

- **Decisions:** none new. It extends what ADR-0018's rule context can read, and ADR-0025's reads cover it; ADR-0018 gets a More Information line.
- **Code:** `cairn-api` (`RuleContext`); `cairn-core` (the rule stage and its views, recorded reads); `cairn-codegen` (base classes); `cairn-example-hr` (the rule, the story and its test).
- **Docs:** the codegen guide's rule section, the HR domain page (rule table, HR-16, gaps), the reference domain page (the test-only rule), and the architecture page.
