---
status: accepted
date: 2026-10-16
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Let relationships of a type outlive their targets, as records of past facts

## Context and Problem Statement

Both ends of a relationship must exist at every point of its effective period (the `relationships` spec), so ending an entity ends, or requires ending, every relationship to it. Some relationships record a fact about the past instead of a link that holds over time: who decided a leave request, who approved an invoice, who reported an incident. In the [HR domain](../reference/hr-domain.md), ending a manager's employment would end every `DecidedBy` pointing to it, and past decisions would drop out of current views; so the generated company never ends a manager's employment.
Which relationships may outlive an end, and what reads return for an end that no longer exists, are part of the schema language, of integrity and of every read, so they are costly to change. The change `add-relationships-that-outlive-their-targets` raised it.

## Decision Drivers

- A record of a past fact stays readable, from the entity it belongs to, after the other party has ended
- Integrity stays checkable in the store: the end existed when the fact was recorded, and a purge still removes everything about a purged entity
- Reads stay uniform: navigation, traversals and queries agree, and the result says that the end has ended
- Nothing changes for relationship types that do not opt in, and existing schemas stay valid

## Considered Options

- Relationship types that declare that their relationships outlive their targets, with the target required at the start of the relationship and read as its last version afterwards
- Copying what is needed of the end into attributes of the source, such as the decider's employee number
- Ending such relationships with their ends, and reading past facts at earlier effective times
- Relationships that may outlive either end

## Decision Outcome

Chosen option: "Relationship types that declare that their relationships outlive their targets", because it keeps the fact as a relationship, with its integrity, and reads it back from the entity it belongs to.

- **The declaration.** An association that is not symmetric MAY declare `outlivesTarget`. A composition cannot: its target ends with its source, and a source that outlived its part would compose nothing. A symmetric type cannot, because its ends are interchangeable.
- **Lifetimes.** The source exists at every point of the relationship's effective period, as for every type. The target exists at the start of each stretch of the relationship's effective period; after that, the relationship MAY continue while the target does not exist. Ending the target does not require ending the relationship, and a composition cascade that ends the target leaves it.
- **Multiplicities.** At the source, the relationship counts for as long as it is effective; at the target, the multiplicities hold while the target exists, as for every entity.
- **Reads.** Navigating from the source returns the relationship with the target's last version as known at the view's basis, whose period ends at or before the view's effective time, so that the caller sees that it has ended. Queries that follow the relationship read the same version, and traversals reach it and go no further, because an entity that does not exist has no relationships to navigate.
- **Schema evolution.** Declaring `outlivesTarget` on an existing type is compatible, because it only relaxes integrity; removing it is breaking, because stored relationships may already outlive their targets.
- **Everything else stays.** A purge of the target removes the relationship as before, and rules read what navigation returns.

### Consequences

- Good, because past facts stay readable from the entity they belong to, at every effective time, after the other party has ended.
- Good, because the decider must exist when the decision starts, so a record cannot point to an entity that never existed then.
- Good, because a caller tells an ended target by its version's period, with no new result type.
- Bad, because a reader that assumed both ends exist must check the period of the target's version for these types.
- Bad, because the source end is never outlived: a type that records its source's past must be declared the other way around.
- Bad, because reading the last version of an ended target reads its timeline, so a correction of it runs the rules that read it again (ADR-0025), which costs time, not correctness.

### Confirmation

- Scenario tests end a target while relationships that outlive it continue, refuse a relationship that starts after its target ended, keep such relationships through composition cascades, and navigate, query and traverse to an ended target, in every kind of store.
- The HR example ends a manager's employment, and her decisions stay.

## Pros and Cons of the Options

### Copying the end's data into attributes

- Good, because it needs nothing new.
- Bad, because the copy has no integrity, is not navigable, and goes stale when the end is corrected; a purge of the end does not reach it.

### Ending the relationship with its end

- Good, because it is the rule of today.
- Bad, because current views lose the fact, and every reader must know at which effective time to look for it.

### Outliving either end

- Good, because the type need not be oriented.
- Bad, because a relationship whose source does not exist is navigable from nothing that exists, and every type can be oriented so that the recorded party is the target.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, and their go-ahead of 2026-10-16 to take the gaps that remained open.
- It relaxes the `relationships` spec's rule that both ends exist throughout a relationship's period, for the types that declare it; [ADR-0010](0010-model-data-as-a-typed-property-graph-with-single-inheritance-and-traits.md) stays in force.
