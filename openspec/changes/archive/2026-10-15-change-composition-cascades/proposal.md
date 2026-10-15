# Proposal

## Why

When an entity ends, the targets of its compositions end with it, but their other relationships do not, so the commit is rejected because those relationships would outlive their ends. In the HR example, Gus leaving (HR-11) must end the decision of his sick leave by its ID, a relationship of a leave request that the caller never named. A target "cannot exist without the source and ends with it" (ADR-0010), so whatever ends the target should end its relationships too.

## What Changes

- A composition cascade also ends every other relationship of each target it ends, at either end, that started before the end and continues past it.
- The entities at the other ends of those relationships are checked for their multiplicities, so a cascade that would leave one without a required relationship is rejected with the usual problem.
- The receipt lists those relationships after the target's own cascade.
- Entities that writes end directly are unchanged: their relationships stay the writer's to end, as RD-8 requires.
- HR-11 no longer ends the sick leave's decision by ID, and the gap leaves the HR domain page.

### Non-goals

- Ending the relationships of entities that writes end directly.
- Relationships that may outlive an end that they only record, another gap of the HR domain page.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `relationships`: composition cascades end the other relationships of the targets they end.

## Impact

- **Decisions:** none new. It follows ADR-0010 (a composition's target ends with its source) and keeps ADR-0007 (one writer) and ADR-0025 (ending relationships runs re-checks of rules that read them).
- **Code:** `cairn-core` `Cascades` and `Preparation`; no API signature changes, but the documented contract of `Receipt.ended` grows; the HR example's `Brightwater` and `CompanyGenerator` no longer end decisions by ID.
- **Example:** HR-11 and the HR domain page.
- **Docs:** the glossary entry for composition cascades, the architecture page and the HR domain page.
