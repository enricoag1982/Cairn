# Proposal

## Why

Cairn is AI-native (ADR-0017): agents must be able to read a schema, understand what it means, build valid changes and correct their own mistakes.
Today a schema holds names and types only, problems carry a bare message, and the only way to see what a registration would do is to try it.
Custom validation is Java code that schema elements name (ADR-0018), but nothing yet lets a schema refer to it.
This change makes the schema describe itself and explain its problems, before schema documents (`add-schema-documents`) give it a text format.

## What Changes

- Descriptions on entity types, traits, relationship types and attributes, and examples on attributes. Examples are checked against the attribute's constraints.
- Constraints for string attributes: allowed values, a pattern, a minimum length and a maximum length.
- Rule references: an entity type or relationship type names the rules that apply to it, each by name and version. The schema registry knows the available rules, lists them, and rejects references to unknown rules or to rules for other types.
- Problems explain themselves: besides the code, path and message, a problem can say what was expected, what was found and how to fix it. A name that refers to nothing comes with the closest known name. Every problem code has an explanation that can be read at runtime.
- Planning a registration: the registry reports what a registration would do (apply, leave unchanged or reject), with every classified change and every problem, without recording anything.
- Compatibility rules for the new properties: relaxing a constraint or removing a rule is compatible; adding or tightening one is breaking.
- The reference domain gains string constraints and two rules, one within one entity and one spanning several entities.

### Non-goals

- Running rules. A rule here is only an available description; rules run at commit with `add-entity-rules`.
- Finding rules through Java's `ServiceLoader`. Applications pass the available rules when they create the registry.
- Schema documents, their JSON Schema and locations inside a document: `add-schema-documents`.
- Constraints for other value types beyond ranges, multi-valued attributes, and expressions in the Common Expression Language (ADR-0018, later).
- The approval policy and agent tools: `add-agent-tools`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `type-model`: string constraints on attributes; descriptions and examples; rule references; problems with expected values, found values and hints; explained problem codes; the list of available rules
- `schema-evolution`: compatibility rules for the new properties, hints on breaking changes, and planning a registration

## Impact

- **Decisions:** builds on ADR-0010 and ADR-0012 (the meta-model), ADR-0017 (commitments 2, 3 and 4: self-description, previews, problems that explain themselves), ADR-0018 (rule references checked at registration) and ADR-0020 (patterns as I-Regexp, matched in linear time). All are accepted.
- **Code:** `cairn-api` gains new components on the definition and schema records, rule references and available-rule descriptions, problem details and plans. `cairn-core` validates, compiles and compares the new properties and plans registrations. The definition records change shape; Cairn has no released users, so no compatibility constructors are kept.
- **Dependencies:** RE2/J (`com.google.re2j:re2j` 1.8) at runtime in `cairn-core`, to match patterns in linear time (ADR-0020).
- **Docs:** the reference domain page (string constraints and rules), the glossary (plan, available rule), the architecture page.
