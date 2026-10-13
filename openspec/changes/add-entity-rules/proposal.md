# Proposal

## Why

Schema documents declare simple constraints; anything more is a Java rule that the schema names (ADR-0018).
`add-self-describing-schema` lets a schema name rules and checks the names against the rules available to the registry, but nothing runs them yet.
This change runs rules at commit, so that the integrity they express holds for every writer, people and agents alike.

## What Changes

- `Rule`, `EntityRule` and `RuleContext` in Java: a rule describes itself (name, version, description, type, problem codes), and an entity rule checks one entity version through a read-only rule context.
- Stores are created with rules. Their descriptions become the rules available to the store's schema registry. Rules can also be found through Java's `ServiceLoader`.
- At commit, after the declarative checks, every rule that applies to a touched entity runs for every version the transaction would produce, against the state it would produce. Rules named by a supertype apply to its subtypes.
- A rule reports problems with the generic code for rule violations and its own code, qualified by its name. A rule that throws an exception rejects the transaction with a problem instead of failing the commit.
- Dry runs run the rules too.
- The reference domain's rule `lei-checksum` is implemented in the test fixtures, with a new story.

### Non-goals

- Rules on relationships and rules spanning several entities through relationships, such as `total-ownership-at-most-100`: `add-relationships`.
- Expressions in the Common Expression Language (ADR-0018, later).
- Checking existing data when a rule is added to a type with data, which would make that change compatible: later.
- Stores that reopen with rules missing: with durable storage.

## Capabilities

### New Capabilities

- `entity-rules`: rules in Java, their registration with a store, and running them at commit

### Modified Capabilities

- `transactions`: the order of problems places rule problems after declarative ones

## Impact

- **Decisions:** implements ADR-0018 for entities. Accepted.
- **Code:** `cairn-api` gains `Rule`, `EntityRule`, `RuleContext` and the problem component for rule codes; `cairn-core` runs rules in the commit path.
- **Dependencies:** none.
- **Docs:** glossary (rule context), the reference domain (story RD-12), the architecture page.
