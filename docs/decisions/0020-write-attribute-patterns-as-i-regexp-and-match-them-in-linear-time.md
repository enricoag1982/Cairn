---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Write attribute patterns as I-Regexp and match them in linear time

## Context and Problem Statement

String attributes can declare a pattern that every value must match (`add-self-describing-schema`).
Patterns are part of the schema, so they are stored, compared for compatibility, exported in JSON Schemas for agents and editors ([ADR-0017](0017-design-cairn-so-that-ai-agents-are-first-class-users.md)), and registered at runtime by agents.
At commit they run inside the single writer ([ADR-0007](0007-run-sysrec-as-an-embedded-single-node-engine-with-a-single-writer.md)): one pattern with catastrophic backtracking, such as `(a+)+b`, can stall every write.
We need to fix the syntax of patterns and how they are matched before stored schemas hold any, because changing the syntax later would be a breaking change.

## Decision Drivers

- The same pattern means the same thing in Cairn and in the JSON Schemas it publishes (ECMAScript regular expressions)
- Matching takes time linear in the input, whoever wrote the pattern
- Agents and people know the syntax
- Few dependencies in the embedded engine

## Considered Options

- I-Regexp (RFC 9485), matched with RE2/J
- I-Regexp, matched with `java.util.regex`
- The full `java.util.regex` syntax
- I-Regexp, matched by an automaton of our own

## Decision Outcome

Chosen option: "I-Regexp (RFC 9485), matched with RE2/J", because I-Regexp is designed for exactly this use (patterns in data models, interoperable with JSON Schema), and RE2/J guarantees linear-time matching.

- A pattern must be a valid I-Regexp. Cairn checks the syntax itself, against the RFC's grammar, and reports a problem otherwise.
- A pattern matches a value as a whole: I-Regexp has no anchors, and every match is implicitly anchored.
- Cairn matches patterns with RE2/J, after the translation the RFC gives for RE2: an unescaped dot outside a character class becomes `[^\n\r]`, and the pattern is wrapped as `\A(?:...)\z`.
- JSON Schemas get the RFC's translation to ECMAScript: the same dot replacement, wrapped as `^(?:...)$`.
- I-Regexp has no multi-character escapes such as `\d`, `\s` or `\w`. A problem about one suggests the replacement from the RFC, such as `[0-9]` for `\d`.

### Consequences

- Good, because a pattern behaves the same in Cairn, in JSON Schema validators and in other I-Regexp implementations.
- Good, because no pattern can make a commit take more than linear time in the length of the value.
- Good, because the syntax is a subset of what most people and agents already write.
- Bad, because some familiar shortcuts (`\d`, `\w`, `\s`, anchors, lookaround, back-references) are not allowed and must be rewritten.
- Bad, because `cairn-core` gains a runtime dependency, RE2/J (`com.google.re2j:re2j`, small and without dependencies of its own).
- Bad, because Cairn maintains a small checker for the I-Regexp grammar.

### Confirmation

- Tests show that patterns outside I-Regexp are rejected with a suggested replacement, and that a pattern known for catastrophic backtracking, such as `(a+)+b`, matches a long non-matching value quickly.
- The JSON Schemas Cairn publishes use the ECMAScript translation, and tests check that a validator gives the same answers as Cairn on sample values.

## Pros and Cons of the Options

### I-Regexp, matched with `java.util.regex`

- Good, because it needs no dependency.
- Bad, because `java.util.regex` backtracks: patterns such as `(a+)+b` take exponential time on some values, even within I-Regexp.

### The full `java.util.regex` syntax

- Good, because Java developers know it, and it is the most expressive.
- Bad, because JSON Schema validators and other languages read many of its constructs differently or not at all.
- Bad, because it allows back-references and lookaround, which no engine can match in linear time.

### I-Regexp, matched by an automaton of our own

- Good, because it needs no dependency, and I-Regexp is regular, so an automaton matches it in linear time.
- Bad, because a correct, Unicode-aware matcher is more code to write and maintain than a grammar checker plus a proven library.

## More Information

- Accepted under the standing approval the user gave on 2026-10-13 to implement the roadmap without asking again, following a drift review of `add-self-describing-schema`.
- RE2/J cannot take every valid I-Regexp safely, so Cairn also limits repeat counts, size, branching and nesting of patterns; the design of `add-self-describing-schema` lists the limits.
- RFC 9485, I-Regexp: an interoperable regular expression format: <https://www.rfc-editor.org/rfc/rfc9485>
- RE2/J: <https://github.com/google/re2j>
