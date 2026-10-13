---
status: proposed
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Validate with declarative constraints and named Java rules

## Context and Problem Statement

Types are defined in schema documents ([ADR-0012](0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)), which can declare simple constraints: required, unique, ranges, patterns, lengths, allowed values and multiplicities.
Real models also need logic that a document cannot declare: the check digits of an identifier, an end date after a start date, or "the owners of a party hold at most 100% together, at every point in effective time".
We need to decide where that logic lives. It should be written in Java, and agents must be able to use it without being able to inject code into a running store ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)).
See the [AI-native research note](../research/2026-10-13-ai-native-schema-and-rules.md).

## Decision Drivers

- Custom logic is written in Java, with the compiler, the editor and unit tests
- Agents can see which logic exists and attach it to types, but cannot run code that nobody reviewed
- The engine enforces every rule on every write, whoever the writer is
- Rules give the same result with every storage implementation, and when data is checked again later
- Rule failures are problems like any other: a code, a path and a message
- Rules can read other entities and must hold at every point in effective time

## Considered Options

- Declarative constraints in the document, plus named Java rules
- An expression language in the document only, such as CEL
- Scripts loaded at runtime, for example JavaScript or Groovy
- Validation left to application code outside the engine

## Decision Outcome

Proposed option: "Declarative constraints in the document, plus named Java rules", because it keeps logic in reviewed Java code while agents compose it by name.

Validation has three levels:

| Level | Where it lives | Written by | Example |
| --- | --- | --- | --- |
| Declarative constraints | The schema document | People and agents, at runtime | required, unique, range, pattern, length, allowed values, multiplicity |
| Java rules | Java classes, named in the document | Developers and coding agents, through code review | LEI check digits; total ownership at most 100% |
| Expressions (later, optional) | The schema document, in CEL | People and agents, at runtime | `endDate == null \|\| endDate >= startDate` |

### Java rules

- A document names the rules that apply to an entity type or relationship type, by name and version:

  ```yaml
  entityTypes:
    Organization:
      supertype: Party
      rules:
        - { name: lei-checksum, version: 1 }  # The LEI's check digits are valid (ISO 17442, MOD 97-10).
  ```

- A rule is a Java class with a name, a version, a description, the type it applies to and the problem codes it reports. The description and the codes live in the class only. A rule works on the Java type generated from the document, or through the generic API.
- Applications register rules when they open a store, or through Java's `ServiceLoader`. Introspection lists the registered rules with their names, versions, descriptions and types, so agents can find them.
- **Checked at registration:** a schema that names an unknown rule or version, or a rule for a different type, is rejected with a problem.
- **Run at commit,** inside the single writer ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)), after the declarative constraints, against the state the transaction would produce, over every effective period it changes. A rule runs when an instance of the type it is attached to is written; a rule that other writes can break is attached to those types too, such as a rule on `Owns` for total ownership.
- **Read-only context:** a rule reads other entities through a rule context, at the same coordinates as the data being checked, and reports problems through it. A rule declares its problem codes, each with an explanation. The codes are qualified by the rule's name, such as `lei-checksum/invalid-check-digits`, and introspection lists them with the rule.
- **Deterministic:** a rule may not use the clock, files, the network, randomness or shared mutable state, except through the rule context. Then it gives the same result with every storage implementation and when data is checked again. Java cannot enforce this fully, so tests run every rule against every storage implementation.
- **Compatibility:** adding a rule to an existing type is a stricter constraint, so it is a breaking change until the engine can check existing data. Removing a rule is compatible. A rule whose meaning changes gets a new version, and moving a document to that version is a schema change classified like any other. Several versions of a rule can be registered at once, so documents move one at a time.
- **A store whose schema names a rule that is not registered** can still be read, but writes to the affected types are rejected with a problem.
- Other custom logic, such as derived attributes, follows the same pattern later: named Java functions referenced from the document. Side effects never run inside the engine; they belong to applications, fed by the change feed.

### Expressions

Expressions in the Common Expression Language (CEL) are a later, optional addition, for checks within one entity that agents can write at runtime without deploying code. They need their own ADR, including the dependency on a CEL library for Java.

### Consequences

- Good, because logic is written, tested and reviewed as Java, with typed access to the generated types.
- Good, because agents compose reviewed logic by name, and the registry checks every reference.
- Good, because rules spanning several entities and effective time get a defined place, which the first design postponed until after version 1.
- Bad, because a store depends on code: the rules its schema names must be on the classpath, or writes to the affected types stop.
- Bad, because determinism is a convention that Java cannot enforce; a careless rule can make results differ between runs.
- Bad, because rules that read many entities slow down commits, inside the single writer.
- Bad, because adding a rule to a type with data stays breaking until checks of existing data exist.

### Confirmation

- The reference domain has at least one rule within one entity and one rule spanning several entities, with scenarios for both.
- Tests show that a schema naming an unknown rule is rejected, and that writes stop for types whose rule is missing.
- The conformance suite runs every rule against every storage implementation.

## Pros and Cons of the Options

### An expression language in the document only

- Good, because agents can write and change checks at runtime, with no build or release.
- Good, because CEL is designed to be safe: no side effects, bounded cost, and implementations for Java and other languages.
- Bad, because it is another language to learn, and rules spanning several entities and time are hard to express in it.
- Bad, because logic in expressions has no editor support, unit tests or refactoring like Java.

### Scripts loaded at runtime

- Good, because scripts are as expressive as Java and need no release.
- Bad, because they run unreviewed code inside the engine. Sandboxing is hard, and determinism cannot be guaranteed.
- Bad, because an agent could add arbitrary behavior to a running store, against the approval policy of [ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md).

### Validation left to application code

- Good, because the engine stays simple.
- Bad, because every writer, including tools and agents, must repeat the checks, and one that forgets them corrupts the data.
- Bad, because checks against the state at commit, across several entities and effective time, cannot be done correctly outside the single writer.

## More Information

- Kubernetes validates custom resources the same way: a declarative schema, then CEL rules, then code in admission webhooks: <https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/#validation-rules>
- Common Expression Language: <https://cel.dev/>
- ISO 17442 (LEI) check digits use ISO 7064 MOD 97-10.
