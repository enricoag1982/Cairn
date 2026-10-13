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

- `Rule` in `io.github.enricoag1982.cairn.data`: `RuleDescriptor descriptor()` and `void check(EntityState version, RuleContext context)`.
- `RuleContext`: `View view()`, a view of the state the transaction would produce, effective at the start of the version's period; `void problem(String code, String attribute, String message)` and `void problem(String code, String message)` for a problem about the whole entity.
- Rules on generated Java types come with `add-java-type-generation`, as adapters over this interface.

### D2. Registration

- `StoreSettings` gains `rules(Rule...)` and `discoverRules()`, which loads `Rule` implementations with `ServiceLoader`; `cairn-core`'s module declares `uses io.github.enricoag1982.cairn.data.Rule`.
- The store passes the rules' descriptors to its schema registry as its available rules. Two rules with the same name and version throw `IllegalArgumentException`.

### D3. Running rules

- After the declarative checks of D6 in `add-entity-versions`, for each touched entity without problems, the engine collects the entity type's effective rules (its own and its supertypes', from the schema at the new state), and runs each one for every segment of the entity's new timeline that differs from its previous timeline.
- The view given to a rule is a provisional view: the latest committed state plus the transaction's working timelines, at a provisional basis one above the latest. It is created once per transaction and is read-only.
- A rule runs synchronously in the writer thread. A slow rule slows every commit; that is the documented cost (ADR-0018).

### D4. Rule problems

- `Problem` gains `Optional<String> ruleCode`. Rule problems have the code `RULE_VIOLATION`, the rule's code qualified by its name in `ruleCode`, and a path built like other data problems (`$gamma.lei`).
- An exception thrown by a rule, or a code the rule does not declare, gives a `RULE_FAILED` problem naming the rule; the exception's message is the found value. The transaction is rejected.

### D5. Reference rules

- The test fixtures gain `LeiChecksumRule` (ISO 7064 MOD 97-10 over the LEI's characters) and `NameNotBlankRule` (for Party), plus descriptors that `ReferenceModel.rules()` returns. `total-ownership-at-most-100` keeps a descriptor only until `add-relationships`.
- The reference domain page gains story RD-12: creating Gamma with the LEI `5493001KJTIIGC8Y1R13`, whose check digits are wrong, is rejected.

## Risks / Trade-offs

- [A rule can read a lot through its view and slow commits] → Documented; measurements come with the durable store.
- [Provisional views mix committed and working state] → They are built from the same timelines the commit will append, so a rule sees exactly the state that would be committed.
