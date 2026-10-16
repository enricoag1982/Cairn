# Design

## Context

ADR-0036 decides this change: attributes declare classifications in the schema; views, queries and commits take a clearance from the caller; what the clearance does not cover is withheld; rules, checks and derivations see everything.

Views (`View`) are immutable and read at their coordinates; `NavigableView` and its subclasses return `EntityState` and `RelationshipState` records, and queries run through `View.query`, checked by the query checker before anything is read. Agent tools (`AgentTools`) open views and submit payloads for the server's principal; the server takes its options from `ServerOptions`. Attribute properties run through definitions, builders, documents, the JSON Schema, the canonical form, validation, compatibility (`PropertyRules`) and stored definitions, as `deprecated` and `derived` do.

## Goals / Non-Goals

**Goals:** no value that a clearance does not cover reaches a restricted reader or is changed by a restricted writer, through any read, query, key or write; nothing changes for callers without a clearance.

**Non-goals:** see the proposal.

## Decisions

### D1. Classifications in the schema

- `AttributeDefinition` gains `Optional<String> classification`, last, with the existing constructors kept; `AttributeBuilder.classification(String)` sets it; the resolved `Attribute.classification()` reports it. Traits' attributes carry theirs into every type that includes them.
- A classification is written like a rule name: lowercase letters, digits and hyphens, starting with a letter. Another name is a problem `INVALID_CLASSIFICATION` at the attribute.
- A derived attribute that reads classified attributes MUST have the classification they have; one that reads attributes of two classifications, or of one that it does not have, is `INVALID_CLASSIFICATION`. `CompiledDerivation` gains `Set<String> attributesRead()`, which `cairn-cel` fills from the expression's selections of `self`.
- Documents write `classification: pay`; the JSON Schema of documents, the document writer, the canonical form and stored definitions (an optional trailing section of format version 1) carry it.
- Adding, changing or removing a classification is compatible (`PropertyRules`).

### D2. Clearances and restricted views

- `Clearance` is a record in `io.github.enricoag1982.cairn.data` holding a set of classifications, with `none()`, `of(String...)`, `covers(Attribute)`, which is true for an attribute without a classification, and `intersect(Clearance)`.
- `View` gains `View restrictedTo(Clearance clearance)` and `Optional<Clearance> clearance()`. A view of a restricted view is restricted to the intersection.
- A restricted view withholds the values of attributes that its clearance does not cover from every `EntityState` and `RelationshipState` it returns: entities, entities by type, timelines, relationships and their timelines, navigation, navigation timelines, traversals (whose condition sees the restricted state) and query rows.
- `EntityState` and `RelationshipState` gain `Set<String> withheld`, last: the names of the attributes whose values were withheld, in the schema's order, whether the version had a value or not. Existing constructors keep compiling, with none withheld.
- Rules' views (`RuleContext.view()`, `before()` and the rest) are never restricted.
- **Classifications added later.** A restricted view withholds an attribute unless its clearance covers the attribute's classification in the view's schema and in the store's latest schema, matched by the attribute's stable identifier. A view at an older basis, from before the attribute was classified or while it had another classification, so withholds what is classified now; a classification that was removed since still applies to views of the time it held. The query checker and key checks use the same rule.

### D3. Queries and keys

- The query checker, given the view's clearance, reports a problem `ACCESS_DENIED` at each place where a query names a withheld attribute: a filter, an order, a group key, an aggregate, a projection or a traversal condition. The query reads nothing.
- `View.entity(UniqueKey)` through a restricted view, for a key of a withheld attribute, throws `WithheldKeyException`, an `IllegalArgumentException`, as a key that the schema does not allow does; agent tools report it as `ACCESS_DENIED`.
- A query result's rows hold restricted states, which list what they withheld.

### D4. Commits

- `Transaction` gains `Optional<Clearance> clearance()`, and its builder `clearance(Clearance)`. A transaction without one writes as today.
- A write in a transaction with a clearance that sets or clears an attribute that the clearance does not cover is rejected with `ACCESS_DENIED` at that attribute of the write, also in a create. Values that the write does not touch carry forward, whatever their classification; a replace carries forward the withheld values that it does not name, and is rejected only for one that it names, so that it cannot clear a withheld value by leaving it out.
- A write that names an entity by a unique key of an attribute that the clearance does not cover is rejected with `ACCESS_DENIED` at the reference, before the key is resolved, so that whether an entity has the value is not revealed.
- The clearance is part of the transaction as submitted: durable stores keep it in a trailing section of data records and keyed transactions, and an idempotency key compares it with the rest, so a retry with another clearance is a key used with different content.
- **Refusal stops the transaction.** When a transaction with a clearance has a problem `ACCESS_DENIED`, the store returns those problems and does nothing else with it: no validation, derivation, check or rule runs over values it may not see, so neither unique constraints nor expressions can be probed through a refused value.
- **Problems carry no withheld values.** For a transaction with a clearance, a problem about an attribute that the clearance does not cover, or from a check or a derivation that reads one, keeps its code, path and, for a check, its name and its author's message, but no value of a withheld attribute, no found value, no evaluation message, and no ID of another entity: unique, range, pattern and required problems of classified attributes, `DERIVATION_FAILED` and `CHECK_FAILED` are reduced so. A violated check still says that it is violated, as ADR-0036 accepts.
- Dry runs apply the same. Rules, checks and derivations run as for every commit, with every value. A receipt names IDs only, so it withholds nothing.

### D5. Agents

- The server takes `--clearance <classification>[,<classification>...]`, empty by default. `AgentTools` takes the clearance; every tool reads through views restricted to it and submits every commit and dry run with it. A payload has no way to give a clearance.
- JSON results of states list `withheld` when it is not empty. `cairn_describe` reports the clearance and, in the schema, each attribute's classification.
- Tool errors for a withheld key or attribute are problems `ACCESS_DENIED`, never protocol errors.
- `cairn_approval_status` answers only for requests that an adapter with the same principal and clearance made, so that adapters sharing a queue cannot read each other's requests.
- An approval request keeps the clearance of the adapter that asked, and approving it commits with that clearance, so an approver cannot widen it.
- `SchemaRegistry` gains `plan` and `register` overloads that take a clearance; `AccessGuard` compares the schemas before and after, under the registry's writer lock, and the agent tools and approval requests use them. For a change that is breaking too, such as a check added to an existing type, `ACCESS_DENIED` comes with `BREAKING_CHANGE`.
- An agent's schema plan or registration that adds, changes or removes a classification that the clearance does not cover, before or after, or adds or changes a check or a derived attribute that reads an attribute of such a classification, is rejected with `ACCESS_DENIED` at the element. Otherwise an agent could declassify pay and then read it, or probe it with a check and dry runs.

### D6. Generated types

A generated type read from a state that withheld an attribute does not fail when it is built, also for a required attribute; its accessor for that attribute throws `IllegalStateException`, which names the attribute and says that it was withheld. The Javadoc of classified attributes' accessors says so.

### D7. The reference domain and the HR example

- **Test-only classifications** for scenarios: Account's `balance` classified `finance`, and Organization's `lei` classified `identity`; each scenario that needs one names it.
- **HR.** `Employment.annualSalary` and `Employment.currency` are classified `pay`. HR-13's agent runs with the clearance `pay`. A new story, HR-18, recorded 2 November: an agent acting as `agent:org-chart`, with no clearance, lists the employments of `PLT` with their numbers and units but without pay, which they list as withheld, and its query of the average salary is rejected with `ACCESS_DENIED`. Application code that reads pay, such as reports, reads through views that are not restricted.

## Risks / Trade-offs

- **A caller that passes no clearance reads everything.** This keeps existing code working; the guides say that code acting for a user must pass one.
- **Problems can reveal facts.** A rule or check that fails over a withheld value tells the writer that something does not hold; schema authors keep values out of messages.
- **When, not what.** Restricted timelines keep the boundaries of versions that differ only in withheld values, conflict checks report a change of a withheld value as a conflict, and the change feed lists such changes: a restricted reader learns when a withheld value changed, never to what.
- **Writes that do not name withheld attributes still change them.** A restricted end-date ends the whole version, and a restricted write to an unclassified input changes a classified derived value. Java code that reads through a restricted view and writes a replace without a clearance clears the values it did not see; such code passes the same clearance to the transaction.
- **Every returned state is filtered.** The cost is a pass over each state's values in restricted views only.
