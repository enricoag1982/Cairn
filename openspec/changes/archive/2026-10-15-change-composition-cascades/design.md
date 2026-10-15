# Design

## Context

`Cascades` ends, for each entity a write ends, the composition relationships from it that go past the instant, their targets, and recursively their targets' compositions, and records the IDs in order for the receipt. Relationships of the targets other than those compositions are left as they are, so `Integrity` reports them as outliving their ends.

## Decisions

### D1. What a cascade ends

After a target's own cascade (its compositions and their targets, depth first), the cascade ends every relationship of the target that starts before the instant and goes past it, of any type, whether the target is its source or its target, in the order of their IDs, and records each in the receipt list. Relationships that the transaction already ends from the instant or earlier are skipped, so writes that end them explicitly, as HR-11 did, still commit; one that a write ends later, or that the transaction relates with a start before the instant, is cut to the instant and listed, as cascaded compositions and targets already were. Relationships that start at or after the instant are not touched and stay problems, as for compositions.

### D2. Checks

The ends of the relationships the cascade ends become touched entities, as a relationship end is, so their multiplicities and lifetimes are checked. The ended relationships are checked as an explicit end of them is; their ends only for multiplicities and lifetimes. Ending changes no values, so no rule runs for the ended relationships, except that a relationship whose type names a rule that the store has no code for is rejected, as an explicit end of it is (`entity-rules`); re-checks run for rules that read those relationships (ADR-0025).

### D3. Entities that writes end

Unchanged: ending an entity directly, from the cascade's instant or earlier, with relationships that continue is still rejected (RD-8), so that a caller decides what happens to an account's holder or an organization's ownerships. Two of its relationships do end without the writer: its compositions, as before, and an association with a cascaded target, which is one of that target's relationships. An entity that a write ends later than a cascade's instant is a target of that cascade, as before.

## Risks / Trade-offs

- A cascade now changes relationships the caller did not name. The receipt lists them, and multiplicity problems name the entity that would lose its relationship.
