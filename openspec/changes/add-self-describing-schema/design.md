# Design

## Context

This change extends the schema API built by `add-type-model` (archived as `2026-10-13-add-type-model`; its design explains the existing classes). The requirements are in `specs/type-model/spec.md` and `specs/schema-evolution/spec.md`.

Constraints that shape the approach:

- ADR-0017, commitments 2 to 4: the model describes itself, changes can be previewed, problems explain themselves.
- ADR-0018: rules are Java code named from the schema by name and version, checked when a schema is registered, and run at commit. Only the check at registration is built here.
- ADR-0012: schema documents (next change, `add-schema-documents`) are read into the definitions extended here, so every new property must be plain data.
- The existing design still holds: validation reports every problem (D3 of `add-type-model`), compatibility is a classified diff in which a property without a rule is breaking (D6), and identifiers are matched by name and alias (D5).

## Goals / Non-Goals

**Goals:**

- Definitions and the resolved schema carry descriptions, examples, string constraints and rule references.
- The registry is created with the rules available to it, lists them, and checks references against them.
- Problems carry expected values, found values and hints; every code has an explanation.
- `plan` reports what `register` would do, sharing its code path so the two cannot drift apart.

**Non-Goals:**

- Executing rules, the rule interface with a `check` method, and `ServiceLoader` discovery: `add-entity-versions`.
- Text formats and locations in documents: `add-schema-documents`.

## Decisions

### D1. New components on the definition records, set through the builders

- `AttributeDefinition` gains `Optional<String> description`, `List<Literal> examples`, `List<String> allowedValues`, `Optional<String> pattern`, `OptionalInt minLength` and `OptionalInt maxLength`.
- `EntityTypeDefinition` and `RelationshipTypeDefinition` gain `Optional<String> description` and `List<RuleReference> rules`. `TraitDefinition` gains `Optional<String> description`.
- New record `RuleReference(String name, int version)`. Its constructor rejects only nulls; the validator checks names and versions, so every problem is reported at once.
- The builders gain `description(String)`, `examples(Literal...)`, `allowedValues(String...)`, `pattern(String)`, `minLength(int)`, `maxLength(int)` and `rule(String name, int version)`, each where it applies.
- Components are added flat, rather than grouped into a record of string constraints: the builders hide the arity, and flat components map one to one to the keys of a schema document.
- Records keep only their canonical constructors. Cairn has no released users, so tests and fixtures are updated instead of keeping overloads.

### D2. Literals for strings and booleans

- `Literal` gains `StringLiteral(String)` and `BooleanLiteral(boolean)`, with `Literal.of(String)` and `Literal.of(boolean)`.
- They are needed for examples. As range bounds they are already rejected: a range on a string or boolean attribute is `RANGE_NOT_ALLOWED`, and a string bound on a numeric attribute is `RANGE_LITERAL_MISMATCH`.
- An example fits an attribute under the same rule as a range bound: same type, an int for a long or decimal attribute, or a long for a decimal attribute.

### D3. Available rules are described by `RuleDescriptor`

- `RuleDescriptor(String name, int version, String description, String appliesTo, List<RuleCode> codes)` and `RuleCode(String code, String explanation)` live in `cairn-api`. `appliesTo` is a type name, matched by name or alias like any reference.
- `SchemaRegistries.inMemory(InstantSource clock, Collection<RuleDescriptor> rules)` creates a registry with available rules. The existing factories create one with none. Two descriptors with the same name and version throw `IllegalArgumentException`, because this is a programming error in the application.
- `SchemaRegistry.availableRules()` lists the descriptors, sorted by name and version.
- A rule's problem codes are qualified by the rule's name when reported (`lei-checksum/invalid-check-digits`). That happens in `add-entity-versions`; here the codes are only listed.
- The executable rule (ADR-0018) will be an interface whose implementations return a `RuleDescriptor`. Keeping the description a plain record lets tools, documents and tests use it without loading rule code.

### D4. Validation of the new properties

- New problem codes:
  - `TEXT_CONSTRAINT_NOT_ALLOWED`: allowed values, a pattern or a length on an attribute that is not a string
  - `INVALID_PATTERN`: a pattern that `java.util.regex.Pattern` does not compile
  - `INVALID_LENGTH`: a negative length, or a minimum above the maximum
  - `DUPLICATE_ALLOWED_VALUE`: an allowed value listed twice
  - `INVALID_EXAMPLE`: an example that does not fit the value type or breaks a constraint
  - `INVALID_RULE_NAME`: a rule name that breaks the naming rule, or a version below 1
  - `UNKNOWN_RULE`: no available rule with that name and version
  - `RULE_TYPE_MISMATCH`: the available rule applies to a type that is neither the naming type nor one of its supertypes
  - `DUPLICATE_RULE`: a type names a rule that it or a supertype already names
- The path of a rule reference is the type name and the rule name joined by a dot (`Organization.lei-checksum`). Rule names contain hyphens and attribute names cannot, so the two never clash.
- A pattern matches a value as a whole (`Matcher.matches`). Patterns should stay within the syntax that Java and JSON Schema (ECMA-262) share, so that `add-schema-documents` can publish them; that is advice, not checked.
- Lengths count Unicode code points (`String.codePointCount`).

### D5. Problem details and the code catalog

- `Problem` gains `Optional<String> expected`, `Optional<String> found` and `Optional<String> hint`. A constructor `Problem(code, path, message)` remains for problems without details, because most problems have none.
- `ProblemCode` gets an `explanation()` method. The explanation text is passed to each constant's constructor and is the one home of what a code means; the constant's Javadoc says the same in one line.
- Details are filled in for at least:

  | Code | expected | found | hint |
  | --- | --- | --- | --- |
  | `INVALID_NAME` | the naming rule | the name | |
  | `INVALID_RULE_NAME` | the naming rule for rules | the name or version | |
  | `INVALID_ORIGIN` | the form of origin names or versions | the value | |
  | `UNKNOWN_REFERENCE` | | the name | the closest existing name, if any |
  | `UNKNOWN_RULE` | | name and version | the available versions of that rule, or the closest rule name |
  | `RANGE_LITERAL_MISMATCH`, `INVALID_EXAMPLE` | the value type or constraint | the literal | |
  | `BREAKING_CHANGE` for a removal | | | deprecate instead, or keep the old name as an alias |
  | `BREAKING_CHANGE` for a new required attribute or constraint | | | keep it optional or unconstrained until migrations exist |

- The closest name uses the Levenshtein distance on lower-cased names and aliases of the elements that could be meant, with a maximum of 2. Ties go to the alphabetically first name. The helper is internal to `cairn-core`.

### D6. Compatibility of the new properties

- `PropertyRules` gets one rule per new component, following the spec lists:
  - descriptions and examples: always compatible
  - allowed values: compatible when the new list is empty, or contains every old value while the old list was not empty; otherwise breaking
  - pattern: compatible when removed or unchanged; breaking when added or changed
  - minimum length: compatible when lowered or removed; maximum length: compatible when raised or removed; otherwise breaking
  - rules: compatible when the new list is a subset of the old one, matched by name and version; breaking when a rule is added or its version changes
- `BREAKING_CHANGE` problems take their hint from the change, so `Change` gains an optional hint.
- `Canonical`, which decides whether a registration is unchanged, compares allowed values and rule references as sets and examples as lists.

### D7. Planning shares the registration path

- `SchemaRegistry.plan(Origin, SchemaDefinition)` returns `SchemaPlan(Outcome outcome, List<SchemaChange> changes, List<Problem> problems)`, with `Outcome` being `APPLY`, `UNCHANGED` or `REJECT`.
- `SchemaChange(String path, String description, boolean compatible, Optional<String> hint)` is the public form of the internal `Change`, which it replaces.
- `register` becomes "plan, then apply if the outcome is `APPLY`", inside the same lock, so that a plan and a registration of the same input always agree.
- For a rollback to an earlier compatible version, the outcome is `UNCHANGED` and the changes are those of the reversed comparison, so the caller sees what the stored version adds.
- A plan for an origin's first registration lists every type it adds as a compatible change.

### D8. Resolved schema

- `Attribute`, `EntityType`, `Trait` and `RelationshipType` carry the new properties.
- `EntityType` and `RelationshipType` gain `rules()`, the references the type names itself, and `effectiveRules()`, which adds those of its supertypes, nearest first. A record `EffectiveRule(RuleReference rule, String declaredBy)` tells where each comes from, as `Attribute.declaredBy` does.

### D9. Reference domain

- The reference domain page gains: the pattern `[A-Z]{3}` on `Account.currency`, the pattern `[A-Z0-9]{18}[0-9]{2}` on `Organization.lei`, the allowed values `Corporate` and `Retail` on `CustomerRole.segment`, and a table of two rules: `lei-checksum` version 1 for Organization, and `total-ownership-at-most-100` version 1 for Owns.
- `ReferenceModel` in the test fixtures gains them, plus `ReferenceModel.rules()` returning the two descriptors. Tests that register the reference model create the registry with these rules. `ReferenceModelTest` checks the new columns against the page.

## Risks / Trade-offs

- [Definition records grow to many components] → Builders are the documented way to create them, and schema documents will be the usual way.
- [Java and JSON Schema regular expressions differ in details] → Documented advice to stay in the shared subset; `add-schema-documents` can warn later.
- [Hints are English text that agents parse] → Hints are for reading. Anything a program needs is in the code, path, expected and found fields.
- [Removing a rule is compatible although data written under it may be checked differently later] → Removing a check never makes existing data invalid, which is what compatibility protects.
