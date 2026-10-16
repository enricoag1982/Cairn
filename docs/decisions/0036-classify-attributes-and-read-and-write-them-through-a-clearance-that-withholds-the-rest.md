---
status: accepted
date: 2026-10-16
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Classify attributes, and read and write them through a clearance that withholds the rest

## Context and Problem Statement

Every reader of a store reads every value, so in the [HR domain](../reference/hr-domain.md) every agent reads pay; the page lists this as the gap "Access". Cairn runs inside an application that authenticates its users ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)), and agents reach it through tools that the application serves ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)), so the store does not know who is asking unless it is told.
Where sensitivity is declared, how a reader's rights reach a view, what a reader sees of a value it may not read, and what a write may change are part of the schema language and of every read, so they are costly to change. The change `add-attribute-access-control` raised it.

## Decision Drivers

- A value that a reader may not read never reaches it: not as a value, not through a filter, a sort, an aggregate, a unique key or a derived value
- Integrity stays whole: rules, checks and derivations see every value, whoever commits
- The schema says what is sensitive; the application says who may read it, because it knows its users
- Callers that pass no rights, such as the application's own code today, read and write as before
- Agents get the least: an agent server reads no sensitive value unless its operator says so

## Considered Options

- Attributes declare a classification in the schema; views, queries and commits take a clearance, a set of classifications, from the caller; what the clearance does not cover is withheld
- Access rules per type and principal kept in the store, with the store authenticating callers
- Separate types for sensitive data, related to the main entity, with access decided by the application per type

## Decision Outcome

Chosen option: "Attributes declare a classification; views, queries and commits take a clearance", because the schema and the application each decide what they know, and the store enforces it at every read and write without knowing users.

- **Classification.** An attribute MAY declare one classification, a name such as `pay`, in the schema document or the builder. An attribute without one is readable by every reader. A derived attribute that reads a classified attribute MUST have the same classification, so that it cannot reveal it; one that reads attributes of two classifications is invalid.
- **Clearance.** A clearance is a set of classifications. A view MAY be restricted to a clearance; a view that is not restricted, as today's views are, reads everything. A view restricted from a restricted view gets the intersection, never more.
- **Withheld values.** A restricted view withholds the values of attributes whose classification the clearance does not cover, everywhere it returns states: entities, timelines, relationships, navigation, traversals and query rows. A state says which of its attributes were withheld, so that a reader tells a withheld value from no value, and generated types refuse to read a withheld one.
- **Queries and keys.** A query through a restricted view that names a withheld attribute, in a filter, an order, a group, an aggregate or a projection, is rejected with a problem `ACCESS_DENIED`, before anything is read, because even a filter would reveal the value. Finding an entity by a unique key of a withheld attribute is refused the same way.
- **Writes.** A transaction MAY carry a clearance. A write in it that sets or clears a withheld attribute is rejected with `ACCESS_DENIED`; values it does not touch carry forward as today. Rules, checks and derivations run with every value, whoever commits. For a writer with a clearance, the problems they give carry no withheld value that the engine knows of: a rule's message about an attribute the writer may not see, and the message of an error, are dropped; the messages of rules about other places are written by their authors, who must not put withheld values in them.
- **Agents.** The agent server takes a clearance from its operator, empty by default, and every tool reads and commits through it; tools say which attributes they withheld, and a payload cannot widen the clearance. An agent's schema change may not add, change or remove a classification that its clearance does not cover, nor add a check or derived attribute over one, and an approved request commits with the clearance of the agent that asked.
- **Evolution.** Adding, changing or removing a classification is compatible: stored values do not change, only who reads them. A restricted view applies an attribute's classification as the store's latest schema has it too, so that reading at an older basis does not reveal what has been classified since.

### Consequences

- Good, because an agent that helps with the org chart never reads pay, by default, while one that hires can be given `pay`.
- Good, because the store refuses a query that would reveal a withheld value, instead of answering it in a way that leaks.
- Good, because integrity is checked over all the data, whoever commits.
- Bad, because the application must pass the right clearance: a view or a transaction that carries none reads and writes everything.
- Bad, because the problems of rules and checks, and the change feed's entity IDs, can still tell a restricted reader or writer that something holds, exists or changed, and timelines and conflict checks tell when a withheld value changed; values are withheld, and problems carry none.
- Bad, because whole types and whole entities are not hidden; per-type and per-row access can follow, on the same clearance.

### Confirmation

- Scenario tests read through restricted views, reject queries and keys over withheld attributes, reject writes of withheld attributes, run rules over withheld values, and serve an agent without a clearance, in every kind of store.
- The HR example classifies pay, and an agent without the clearance reads people and units without it.

## Pros and Cons of the Options

### Access rules and authentication in the store

- Good, because the store would enforce access without trusting the caller.
- Bad, because Cairn is a library inside the application, which already authenticates its users; a second identity system would have to be kept in step with it.

### Separate types for sensitive data

- Good, because it needs nothing new.
- Bad, because the model bends to access, relationships multiply, and a query that joins the types still reveals the values to whoever runs it.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, and their go-ahead of 2026-10-16 to take the gaps that remained open.
- It adds to [ADR-0013](0013-read-through-immutable-views-and-take-feature-flags-resolved-by-the-caller.md) that a view may be restricted to a clearance, and to ADR-0017 that agents read through one; both stay in force.
- Revised on 2026-10-16, before `add-attribute-access-control` was archived, after its drift review: agents may not change classifications or expressions over attributes that their clearance does not cover, which would otherwise let them declassify a value and read it; approval requests keep the clearance of the agent that asked.
- Revised on 2026-10-16 again, after a review that probed for leaks: a restricted view applies classifications of the latest schema as well as its own; a transaction refused with `ACCESS_DENIED` runs no validation, derivation, check or rule; problems of restricted transactions carry no withheld value, found value, evaluation message or other entity's ID; and the consequences say that the timing of changes shows.
- Revised on 2026-10-16 a third time, after a second review that probed for leaks: checks and derived attributes on abstract types are guarded too; a rule's message about a withheld attribute, and an error's message, are dropped for writers with a clearance; refusal comes before the idempotency key is compared.
