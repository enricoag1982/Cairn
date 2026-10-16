---
status: accepted
date: 2026-10-15
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Write checks within one version in CEL, evaluated by an optional module

## Context and Problem Statement

Validation has three levels ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)): declarative constraints in the schema document, Java rules that documents name, and, later, expressions in the Common Expression Language (CEL) for checks within one entity that people and agents write at runtime. ADR-0018 asks for an ADR of their own, including the dependency on a CEL library for Java.
The [HR domain](../reference/hr-domain.md) shows the need: "the last day of a leave is not before the first" is a Java rule, `leave-dates-in-order`, that a developer must write, review and deploy, although it compares two attributes of one version.
How expressions see values, when they run, what problems they report, how schemas evolve with them, and which library runs them are costly to change once documents use them. The change `add-cel-expressions` raised it.

## Decision Drivers

- People and agents write a check in the schema document, and learn at registration, with a located problem, if it cannot work ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md))
- A check is safe to accept at runtime: no side effects, no access to other data, bounded cost
- The same result with every storage implementation, and no new kind of re-check ([ADR-0025](0025-run-a-rule-again-when-the-data-it-read-changes.md))
- `cairn-core` keeps RE2/J as its only runtime dependency; applications that write no checks load no expression library
- A standard language that agents already know, with an implementation maintained for Java

## Considered Options

- CEL checks over one version's values, compiled at registration and run at commit by an optional module `cairn-cel` on Google's CEL for Java
- The same, with CEL in `cairn-core`
- Declarative comparisons between attributes, such as `lastDay >= firstDay`, without an expression language
- Only Java rules, as today

## Decision Outcome

Chosen option: "CEL checks over one version's values, compiled at registration and run at commit by an optional module", because it gives agents a standard, safe language now, while applications that do not use it keep `cairn-core`'s single dependency.

- **What a check is.** An entity type or a relationship type MAY declare *checks*, each with a name (as a rule name), a CEL expression and an optional message. A check applies to the type and its subtypes, like a rule. Every version of a value of the type, that is every segment of its timeline that a transaction writes, MUST make the expression true.
- **What it sees.** One variable, `self`: a map from the names of the version's attributes that have a value to those values. An optional attribute without a value is absent, so expressions test it with `has(self.name)`. Strings, booleans and allowed values are CEL strings and booleans, `int` and `long` are CEL integers, `decimal` values are CEL doubles, and `date` and `instant` values are CEL timestamps, a date at the start of its day in UTC. A check sees nothing else: no other version, entity or relationship, and no ends of a relationship; checks that need more are Java rules.
- **At registration.** The registry compiles every check of a definition: an expression that does not parse, that names an attribute the type does not have through `self.<name>`, or whose result cannot be a boolean, is a problem located at the check, with the position in the expression, and in the document when one was read. Without an expression module the registry rejects every definition that declares checks, with a problem that says which module to add.
- **At commit.** Checks run with the declarative constraints, before Java rules, on every segment that a transaction writes. A false result is a problem with the code `CHECK_VIOLATED`, the check's name and its message; an evaluation error, such as reading an absent attribute without `has`, or exceeding the cost limit, is a problem with the code `CHECK_FAILED` and the error. A store whose schema declares checks, opened without the module, rejects writes to the types that declare them, as for rules without code ([ADR-0018](0018-validate-with-declarative-constraints-and-named-java-rules.md)).
- **Schema evolution.** Adding a check to an existing type, or changing its expression, is a breaking change, as adding a rule is; removing a check or changing only its message is compatible.
- **Packaging.** The module `cairn-cel` depends on the interface for expression languages that `cairn-api` publishes and on `dev.cel:cel`, Google's CEL for Java, and the engine finds it through `ServiceLoader`. The agent server, the code generator of documents with checks, and applications that want checks put it on their class path.

### Consequences

- Good, because agents write checks such as `self.lastDay >= self.firstDay` at runtime, with problems located in their document, and without deploying code.
- Good, because a check reads only the version it checks, so it needs no re-checks, and every storage implementation gives the same result.
- Good, because applications that write no checks do not load CEL or its dependencies.
- Bad, because `cairn-cel` brings Guava, protobuf-java, the ANTLR runtime and more onto the class path of the applications that use it, with their versions.
- Bad, because decimals become doubles in checks, so an exact decimal comparison, such as of shares that must add up, stays a Java rule.
- Bad, because a schema that declares checks needs the module wherever a store of it is opened, or writes to its types are rejected.

### Confirmation

- Scenario tests register checks, reject invalid ones with located problems, and reject writes that violate them, in every kind of store.
- The HR example replaces the Java rule `leave-dates-in-order` with a check.

## Pros and Cons of the Options

### CEL in `cairn-core`

- Good, because every store can run checks without another module.
- Bad, because every application then loads CEL's dependencies, whether it writes checks or not.

### Declarative comparisons between attributes

- Good, because no dependency is needed.
- Bad, because each new kind of check needs a change to the document format, and the format slowly grows into an expression language of its own.

### Only Java rules

- Good, because nothing changes.
- Bad, because a check within one version still needs a developer, a review and a deployment, which is what ADR-0018 wanted to avoid for simple checks.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again; the user agreed to CEL expressions in the roadmap on 2026-10-15, with the proposal of `add-cel-expressions`.
- This decision completes the third level of ADR-0018, which stays in force.
- Checks across versions or entities, functions of Cairn's own in expressions, and derived attributes written in CEL can follow.
- Revised on 2026-10-15, before `add-cel-expressions` was archived, after building it:
  - `cairn-cel` runs on the class path only: CEL's jars have no module names and split packages, so they cannot be named modules; the engine's modules on the module path still find it.
  - CEL for Java has no runtime cost tracking, so the cost limit counts evaluation steps, at most 10,000.
  - `self` is declared as a type with one typed field per attribute, so type errors are found at registration too.
  - A store opened without the module rejects writes to every type a check applies to, subtypes included, and a recorded check that the module cannot compile any more makes those writes fail with `CHECK_FAILED`.
  - A check name must not be the name of an attribute or a rule of its type, because problems name all three by `Type.name`.
  - Schema records of the commit log and snapshot files keep checks in an optional trailing section of format version 1, as receipts keep unique keys, so records without checks read as before; nothing released reads version 1 without it.
- Common Expression Language: <https://cel.dev/>. CEL for Java: <https://github.com/google/cel-java>. Kubernetes validation rules in CEL, which read `self` the same way: <https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/#validation-rules>.
- [ADR-0031](0031-derive-attributes-from-a-version-s-own-values-in-cel-computed-at-commit-and-stored.md) derives attributes from a version's own values with the same expressions, computed at commit and stored.
