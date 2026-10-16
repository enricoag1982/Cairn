---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Run a rule again when the data it read changes

## Context and Problem Statement

A Java rule runs when a transaction changes the values of an entity or relationship whose type names it ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)). Many rules read other data: `decided-by-manager` of the [HR domain](../reference/hr-domain.md) checks a leave decision against the managers of the requester's units on the request's first day. A change to that other data does not run the rule, so it can break the rule unnoticed: ending Cleo's `ManagedBy` of Platform after she approved Dev's vacation commits.
ADR-0018 says that a rule that other writes can break is attached to those types too. That does not work as written: a rule is for one type, the types that name it must be that type or its subtypes, `DecidedBy`, `ManagedBy` and `LeaveRequest` share no supertype, and a write that only ends something runs no rules, though ending is what breaks `decided-by-manager`. Building the HR example (change `add-hr-example`) found this; the change `add-rule-rechecks` closes it.

## Decision Drivers

- The engine enforces every rule on every write, whoever the writer is (ADR-0018), including writes that only end something
- A rule author writes the check of the rule's own type, and does not have to work out every write that can break it
- The same result with every storage implementation, and no change to how durable stores recover ([ADR-0022](0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md))
- Commits stay proportional to what they change, not to the size of the store

## Considered Options

- Record what a rule reads, and run it again when a transaction changes that
- Name a rule on every type whose writes can break it, of either kind, and run it for every write of those types, ends included
- Rules declare the types they read, and run again for every version of their type when one of those is written
- Rules declare paths from their type to what they read, which the engine follows backwards from each change

## Decision Outcome

Chosen option: "Record what a rule reads, and run it again when a transaction changes that", because it is the only option that finds every write that can break a rule without asking the rule's author to describe its reads a second time.

- **What is recorded.** While a rule checks a version of an entity or relationship, the store records what it reads through its rule context: an entity, a relationship, the relationships that navigation finds from one entity by one relationship type in one direction, and the entities of a type, each at an effective time, or across effective time for timelines. Every state that a read returns counts as a read of that entity or relationship. Traversals and queries are made of these reads.
- **When a rule runs again.** A transaction changes a read when, at an effective time the read covers, it changes the values of what was read, or creates or ends an entity or relationship that the read would find. Then the store runs the rule again for the version whose check made the read, as it runs rules for what the transaction writes, against the state the transaction would produce. This is a *re-check*. Ending counts: ending `ManagedBy` changes what navigation from the unit finds.
- **Reads are replaced.** Each check of a version replaces what was recorded for it. Rules do not write, so re-checks never cause more re-checks.
- **Problems.** A re-check's problems are about the version it checks, whose path is its ID when the transaction does not name it. A rule that has reported a problem about something the transaction writes is not run again for other versions in that transaction, because that problem already rejects it and says what is wrong.
- **Rules stay with the type they check.** A rule is named only by the type it checks and its subtypes. Naming a rule on the types whose writes can break it, as ADR-0018 says, is no longer needed. The rest of ADR-0018 stands.
- **Where reads live.** In memory, with the store, not in its files. A durable store learns them after recovery by running the rules it has code for over every version whose type names one, without judging the verdicts; recovery itself still only applies effects (ADR-0022). A purge removes the reads of and by what it purges.
- **Rules without code** record nothing. Writes to the types that name them are rejected, as before, but other writes that would break them cannot be found.

### Consequences

- Good, because a rule protects its verdict against every write, including ends and changes far from its type, and its author writes only the check.
- Good, because what a rule depends on is found from the code that runs, so it cannot drift from what the rule really reads.
- Good, because the same mechanism can tell derived attributes, on the roadmap, when to compute again.
- Bad, because stores keep the reads in memory, as much as all checked versions read.
- Bad, because a durable store runs every rule once when it opens, so opening takes longer as data grows.
- Bad, because a rule that lists every entity of a type, or reads a timeline, runs again whenever any of them changes; rules should navigate from what they check rather than list.
- Bad, because a transaction can be rejected for a version it does not name, and data that broke a rule before this decision rejects writes that reach it, until it is corrected.
- Bad, because a store without a rule's code cannot protect that rule from writes to other types.

### Confirmation

- The HR example shows that ending Cleo's `ManagedBy` of Platform from 10 March is rejected with `decided-by-manager/not-a-manager` about her decision.
- Tests show a re-check for each kind of read and each kind of change, that a check replaces the reads of the version, that duplicate problems are not reported, and that a durable store reopened behaves as before it closed.

## Pros and Cons of the Options

### Name a rule on every type whose writes can break it

- Good, because the engine stays simple: it runs rules for what transactions write.
- Bad, because the rule must find, from each written thing, every version it may break, across effective time: the inverse of its own reads, written by hand, which misses cases silently.
- Bad, because rules then check things of several kinds and types, so the rule interfaces, generated base classes and problems about the written thing all become more complex.

### Rules declare the types they read

- Good, because what a rule depends on is visible in its description.
- Bad, because every write of such a type runs the rule for every version of its own type, so commits slow down as the store grows.

### Rules declare paths that the engine follows backwards

- Good, because the engine runs again only what a change can reach, as Odoo does for computed fields.
- Bad, because the declared paths can drift from what the Java code reads, and cannot say that a rule reads at a time taken from a value, such as a request's first day.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, with the proposal of `add-rule-rechecks`.
- This decision replaces the sentence of ADR-0018 that a rule that other writes can break is attached to those types too, and adds to ADR-0022 what a store does after recovery. Both stay in force for everything else and refer to it.
- Keeping the reads in the store's files, so that opening does not run rules, can follow if opening becomes too slow; it would not change behavior.
- Recording reads to compute again only what a change affects is how incremental computation frameworks such as Salsa work: <https://salsa-rs.github.io/salsa/>. Odoo declares the dependencies of computed fields as paths: <https://www.odoo.com/documentation/17.0/developer/reference/backend/orm.html#odoo.api.depends>.
- [ADR-0031](0031-derive-attributes-from-a-version-s-own-values-in-cel-computed-at-commit-and-stored.md) derives attributes from a version's own values only, so they need no recomputation; derived values that read other data, recomputed through these reads, can follow.
