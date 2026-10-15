# Design

## Context

`Cascades` ends, for each entity a write ends, the composition relationships from it that go past the instant, their targets, and recursively their targets' compositions, and records the IDs in order for the receipt. Relationships of the targets other than those compositions are left as they are, so `Validation` reports them as outliving their ends.

## Decisions

### D1. What a cascade ends

After a target's own cascade (its compositions and their targets, depth first), the cascade ends every relationship of the target that starts before the instant and goes past it, of any type, whether the target is its source or its target, in the order of their IDs, and records each in the receipt list. Relationships that the transaction already ends from the instant or earlier are skipped, so writes that end them explicitly, as HR-11 did, still commit. Relationships that start at or after the instant are not touched and stay problems, as for compositions.

### D2. Checks

The ends of the relationships the cascade ends become touched entities, as a relationship end is, so their multiplicities and lifetimes are checked. Nothing else is validated again. Rules do not run for ends, but re-checks run for rules that read those relationships (ADR-0025).

### D3. Entities that writes end

Unchanged: ending an entity directly with relationships that continue is still rejected (RD-8), so that a caller decides what happens to an account's holder or an organization's ownerships.

## Risks / Trade-offs

- A cascade now changes relationships the caller did not name. The receipt lists them, and multiplicity problems name the entity that would lose its relationship.
