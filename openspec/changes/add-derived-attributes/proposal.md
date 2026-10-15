# Proposal

## Why

Some attributes follow from others of the same version, and every caller composes them today: the HR example's full name of a person is one. ADR-0031 decides derived attributes: CEL expressions over a version's own values, computed at commit and stored, so that history stays reproducible and queries, constraints and rules see derived values like any other.

## What Changes

- An attribute MAY be derived: it states a CEL expression over `self`, which sees the type's attributes that are not derived. Its value is computed at commit for every segment a transaction writes, before constraints, checks and rules, and stored with the version.
- The registry compiles derived expressions with the expression language of checks, and rejects ones that do not compile, read derived attributes, or cannot give the attribute's value type.
- A write that sets a derived attribute is rejected; a result that does not fit the attribute, a missing value for a required one, or an evaluation error rejects the transaction.
- Adding a derived attribute to an existing type, making an attribute derived or changing a derived expression is breaking; making a derived attribute plain is compatible.
- The problem code for a missing expression language becomes `EXPRESSIONS_UNAVAILABLE`, for checks and derived attributes alike.
- Schema documents gain `derived` on attributes; generated JSON Schemas mark derived attributes read-only and keep them out of writes; generated Java builders have no setter for them.
- The HR example derives a person's `fullName`.

### Non-goals

- Derived attributes that read other derived attributes, other versions, entities or relationships.
- Migrations that derive values for versions already stored.
- Derived values from Java functions.

## Capabilities

### New Capabilities

- `derived-attributes`: attributes computed from a version's own values at commit.

### Modified Capabilities

- `checks`: the shared problem code for a missing expression language.
- `schema-documents`: `derived` on attributes.
- `schema-evolution`: adding, changing and removing derivations.

## Impact

- **Decisions:** ADR-0031, accepted under the standing approval with this proposal; it relies on ADR-0008, ADR-0018, ADR-0025 and ADR-0030, and replaces, for derived attributes, ADR-0018's expectation of Java functions.
- **Code:** `cairn-api` (`derived` on `AttributeDefinition`, builders and `Attribute`, the expression interface, problem codes); `cairn-core` (compiling derivations, computing them at commit, evolution); `cairn-cel` (derivations and their result types); `cairn-documents` (documents, JSON Schemas, payloads); `cairn-codegen` (builders); `cairn-example-hr`.
- **Docs:** a section of the guide to writing checks or a guide of its own, the glossary (Derived attribute), the architecture page, the HR and reference domain pages, AGENTS.md, README.md and `openspec/config.yaml`.
