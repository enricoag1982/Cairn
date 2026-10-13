# Proposal

## Why

A Java rule runs only when a transaction changes the values of an entity or relationship whose type names it. Rules that read other data can therefore be broken by writes elsewhere, unnoticed. The HR example found it: ending Cleo's `ManagedBy` of Platform after she approved Dev's vacation commits, though her decision no longer meets `decided-by-manager`.
ADR-0018 meant such rules to be attached to every type whose writes can break them, but that cannot be written: a rule is for one type, the types that name it must share it as a supertype, and a write that only ends something runs no rules. ADR-0025 decides instead that the store records what each rule reads and runs it again when a transaction changes that.

## What Changes

- While a rule checks a version, the store records what it reads through its rule context: entities, relationships, what navigation finds, and the entities of a type, at effective times or across effective time.
- A transaction that changes something a rule read, at an effective time the rule read it, runs the rule again for that version (a *re-check*), ends included. Its problems are about that version, by its ID, with a hint that the transaction changed what the rule read.
- Re-checks run after the rules of what the transaction writes, only when the transaction has no problems other than from rules, and not for a rule that already reported a problem about something the transaction writes.
- A durable store learns the reads after recovery, by running its rules once over every version without judging them.
- The HR example's gap is closed: ending Cleo's `ManagedBy` is rejected.

### Non-goals

- Keeping reads in the store's files, so that opening runs no rules (ADR-0025: later, if opening becomes slow).
- Running rules again after a schema registration or a purge: neither runs rules today, and both keep that.
- Protecting a rule whose code the store does not have from writes to other types.
- The other gaps of the HR domain page, such as the previous version in the rule context.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `entity-rules`: rules run again when what they read changes; a write that only ends something can run rules of others; a rule whose verdict depends on other relationships no longer has to be named by their types.
- `transactions`: the problems of re-checks come last.
- `durability`: after recovery, a store runs its rules once to learn what they read.

## Impact

- **Decisions:** ADR-0025, accepted under the standing approval with this proposal; it replaces ADR-0018's sentence on attaching rules to the types whose writes can break them and adds a step after ADR-0022's recovery, and both refer to it. It relies on ADR-0007 (one writer runs rules), ADR-0008 (effective time) and ADR-0024 (purges run no rules).
- **Code:** `cairn-core` records the reads of rules in the provisional view, keeps them with the store, finds the re-checks of each transaction, and rebuilds the reads after recovery; the javadoc of `Rule`, `EntityRule`, `RelationshipRule` and `RuleContext` in `cairn-api` says so. No API is added and no dependency.
- **Example:** the HR-5 test asserts the rejection, and the gap leaves the HR domain page.
- **Docs:** the glossary (re-check, rule read), the reference domain (two test-only rules), the HR domain page, the architecture page and the guide for durable stores.
