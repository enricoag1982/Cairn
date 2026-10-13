# Design

## Context

ADR-0018 decides that custom validation is Java code that schemas name by name and version, checked at registration and run at commit. `add-self-describing-schema` built the registration side (`RuleDescriptor`, rule references, available rules); `add-entity-versions` built commits and views. This change connects them. The requirements are in `specs/entity-rules/spec.md`.

## Goals / Non-Goals

**Goals:**

- A small, typed interface for rules on entities, usable with the generic API.
- Rules run inside the commit path, after the declarative checks, with a read-only view of the state the transaction would produce.

**Non-Goals:**

- Rules on relationships: `add-relationships` extends the same interface.
- Enforcing determinism. It stays a documented contract (ADR-0018).

## Decisions

### D1. The interface

- `Rule` in `io.github.enricoag1982.cairn.data` is the base interface, with `RuleDescriptor descriptor()`. `EntityRule extends Rule` adds `void check(EntityState version, RuleContext context)`. `add-relationships` adds `RelationshipRule` the same way, so each rule gets a typed subject.
- `RuleContext`: `View view()`, a view of the state the transaction would produce, effective at the start of the version's period; `View viewAt(Instant effectiveAt)`, the same state at another effective time, so that a rule can check every point of the version's period where other data changes; `void problem(String code, String attribute, String message)` and `void problem(String code, String message)` for a problem about the whole entity.
- Rules on generated Java types come with `add-java-type-generation`, as adapters over this interface.

### D2. Registration

- `StoreSettings` gains `rules(Rule...)` and `discoverRules()`, which loads `Rule` implementations with `ServiceLoader`; `cairn-core`'s module declares `uses io.github.enricoag1982.cairn.data.Rule`.
- A rule whose descriptor applies to an entity type must be an `EntityRule`. A store cannot check this when it is created, because `appliesTo` is a type name and the schema comes later; the first write that would run such a rule is rejected with `RULE_FAILED`, which names the rule. Duplicate or malformed rules do fail creation.
- `discoverRules()` sets a flag, and the builder loads the rules with `ServiceLoader` when it builds the settings, so the result does not depend on the order of calls. A rule given and also discovered is a duplicate.
- `StoreSettings.availableRules(Collection<RuleDescriptor>)`, built by `add-entity-versions`, stays: it declares rules whose code the store does not have, such as a rule for relationships before `add-relationships`. Schemas may name them, but a write that would need to run one is rejected with `RULE_UNAVAILABLE`, as ADR-0018 says for stores whose rules are missing.
- The store passes the descriptors of its rules and its declared rules to its schema registry as its available rules. Two with the same name and version throw `IllegalArgumentException`.

### D3. Running rules

- After the declarative checks of D6 in `add-entity-versions`, for each touched entity without problems, the engine collects the entity type's effective rules (its own and its supertypes', from the schema at the new state), and runs each one for every segment of the entity's new timeline whose values differ from the previous timeline at some instant (`Timeline.changedSince`). Splitting a segment, setting the same values, or ending the entity runs no rules. Rules run only for entities without declarative problems, and their problems come after all declarative problems: entity by entity, then the type's effective rules (its own first), then segments in timeline order.
- The view given to a rule is a provisional view: a `View` over the latest committed timelines overlaid with the transaction's working timelines from `Preparation`, with the schema the transaction would produce. Its `basis()` is the prospective basis from `CommitSequence.next()`, and working segments carry it as their recorded basis. A provisional view is only valid while the rule runs: in a dry run, or if the commit is rejected, that basis later names another commit, so rules must not keep it. It is created once per transaction and is read-only.
- A rule runs synchronously in the writer thread. A slow rule slows every commit; that is the documented cost (ADR-0018).

### D4. Rule problems

- `Problem` gains `Optional<String> ruleCode`. Rule problems have the code `RULE_VIOLATION`, the rule's code qualified by its name in `ruleCode`, and a path built like other data problems (`$gamma.lei`, `@write.1.lei` for an entity created without a temporary ID, or the entity's ID).
- An exception thrown by a rule, or a code the rule does not declare, gives a `RULE_FAILED` problem naming the rule; the exception's message, or the code, is the found value. The transaction is rejected. A rule that throws is not run for that entity's remaining segments, and its other reports from that run are dropped. Identical problems from several segments are reported once. A declared rule without code gives `RULE_UNAVAILABLE` once per entity and rule.

### D5. Reference rules

- The test fixtures gain `LeiChecksumRule` (ISO 7064 MOD 97-10 over the LEI's characters) and `NameNotBlankRule` (for Party). `total-ownership-at-most-100` stays a declared rule (`availableRules`) until `add-relationships` implements it; `ReferenceModel.rules()` keeps returning the descriptors, and a fixture helper builds store settings with the executable rules plus the declared one.
- The reference domain page gains story RD-12: creating Gamma with the LEI `5493001KJTIIGC8Y1R13`, whose check digits are wrong, is rejected.

## Risks / Trade-offs

- [A rule can read a lot through its view and slow commits] → Documented; measurements come with the durable store.
- [Provisional views mix committed and working state] → They are built from the same timelines the commit will append, so a rule sees exactly the state that would be committed.
