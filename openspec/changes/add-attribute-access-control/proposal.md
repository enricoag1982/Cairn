# Proposal

## Why

Every reader of a store reads every value: in the HR example every agent reads pay, and the HR domain page lists this as the gap "Access". The store does not know who is asking, because the application authenticates its users and agents reach the store through tools that it serves.

## What Changes

- An attribute MAY declare a *classification*, a name such as `pay`. A derived attribute that reads a classified attribute has its classification.
- A *clearance* is a set of classifications. `View.restrictedTo(Clearance)` gives a view that withholds the values of attributes whose classification the clearance does not cover, in every state it returns; each state lists what it withheld. Views that are not restricted read everything, as today.
- A query through a restricted view that names a withheld attribute is rejected with `ACCESS_DENIED`; finding by a unique key of a withheld attribute fails.
- A transaction MAY carry a clearance; a write in it that sets or clears a withheld attribute is rejected with `ACCESS_DENIED`. Rules, checks and derivations still see every value.
- The agent server takes a clearance from its operator, empty by default; every tool reads and commits through it, and its results list what was withheld.
- Generated Java types refuse to read a withheld attribute.
- The HR example classifies pay: the hiring agent of HR-13 has the clearance `pay`, and a new story, HR-18, serves an org-chart agent without it.

### Non-goals

- Authentication, roles or principals kept in the store.
- Hiding whole types, entities or relationships, or their existence.
- Restricting purges, schema registrations or the change feed, which hold no values.

## Capabilities

### New Capabilities

- `attribute-access`: classified attributes, clearances, restricted views, queries, keys and commits, and agents' clearances.

### Modified Capabilities

- `schema-documents`: the `classification` of attributes and its default.
- `schema-evolution`: classifications are compatible to add, change or remove.

## Impact

- **Decisions:** ADR-0036, accepted under the standing approval: attributes declare classifications, and views, queries and commits take a clearance. It adds to ADR-0013 and ADR-0017.
- **Code:** `cairn-api` (definitions and builders, `Clearance`, `View`, `EntityState`, `RelationshipState`, `Transaction`, problem codes, the expressions interface); `cairn-core` (validation, compatibility, stored definitions, restricted views, query checks, commits); `cairn-cel` (the attributes an expression reads); `cairn-documents` (documents, JSON Schema, JSON results); `cairn-agent-tools` (the server option and every tool); `cairn-codegen` (withheld values in generated types); `cairn-example-hr` (classified pay, HR-13, HR-18).
- **Docs:** the glossary (classification, clearance, withheld), the agent server guide, the query and codegen guides, the HR domain page (types, HR-18, gaps), the reference domain page, the architecture page.
