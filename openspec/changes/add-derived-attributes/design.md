# Design

## Context

ADR-0031 decides derived attributes: a CEL expression over the version's own values that are not derived, computed at commit for every written segment and stored. Checks (ADR-0030) already compile CEL against a type `self` with one typed field per attribute, through `ExpressionLanguage` in `cairn-api`, implemented by the class-path module `cairn-cel`.

## Goals / Non-Goals

**Goals:**
- Derived attributes in definitions, documents and builders.
- Compiling them at registration and computing them at commit, in every kind of store.
- Keeping them out of writes, payload schemas and generated builders.
- The evolution rules, and the HR example.

**Non-goals:** see the proposal.

## Decisions

### D1. The definition

- `AttributeDefinition` gains `Optional<String> derived`, the expression, as its last component, with the old constructor kept. `AttributeBuilder.derived(expression)` sets it, and the resolved `Attribute` reports `derived()`.
- A derived attribute keeps every other property: required, unique, range, allowed values, pattern, lengths, description and examples. Constraints apply to the computed value.
- An expression is not blank and has at most 4,000 characters, as for checks.
- New problem codes, each with an explanation:
  - `INVALID_DERIVATION`: does not compile, reads a derived or unknown attribute, or its type cannot give the attribute's value type. The path is `Type.attribute`.
  - `DERIVED_ATTRIBUTE_WRITTEN`: a write sets it.
  - `DERIVATION_FAILED`: an evaluation error, a result that does not fit, or no value for a required attribute.
- `CHECKS_UNAVAILABLE` is renamed `EXPRESSIONS_UNAVAILABLE` and covers both checks and derived attributes, with its hint naming `cairn-cel`.

### D2. The expression interface

- `ExpressionLanguage` gains `CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes, ValueType result)`.
- `CompiledDerivation.evaluate(Map<String, Value> values)` returns `Optional<Value>`, empty for a CEL `null`, and throws on an evaluation error or a result that does not fit.
- The exceptions become `ExpressionCompilationException` and `ExpressionEvaluationException`. Nothing is released, so the old names go.
- `cairn-cel` maps the result as in this table. The expected CEL type is given to the checker, so a mismatch is found at compilation. `null` is accepted when the attribute is optional.

| Attribute type | CEL result |
| --- | --- |
| `string`, attributes with allowed values | string |
| `boolean` | bool |
| `int` | int, which must lie in the range of a Java `int` at evaluation |
| `long` | int |
| `decimal` | double, converted with `BigDecimal.valueOf(double)`; NaN and infinities do not fit |
| `instant` | timestamp |
| `date` | timestamp, which must be at 00:00 UTC |

### D3. At registration

The registry compiles each derived attribute for each concrete type that has it, own, inherited or from a trait.

- It compiles against the type's attributes that are not derived. Reading a derived attribute through `self` is `INVALID_DERIVATION`, as is reading an unknown one.
- Without a language, a definition that declares derived attributes is rejected with `EXPRESSIONS_UNAVAILABLE` at each one, for the registering origin's types, as checks are.
- Compiled derivations are kept with the compiled schema, next to the compiled checks.

### D4. At commit

After the writes of a transaction are applied to the working timelines and composition cascades have run, and before value constraints, checks and rules, the store computes each derived attribute of the type for every segment that the transaction wrote. These are the segments whose values or recording differ from before, as checks select them. It replaces the derived values in those segments with the results, or with nothing.

- A patch or replace that names a derived attribute is rejected with `DERIVED_ATTRIBUTE_WRITTEN` at the write, and the derivation does not run for it.
- A create gives no derived values; they are computed.
- Segments that a cascade only cuts keep their stored derived values.
- A dry run computes and reports the same.
- A store opened without a language rejects every write to a type with derived attributes, with `EXPRESSIONS_UNAVAILABLE`.
- Recovery applies stored effects, so derived values are recovered as they were stored, without computing them again (ADR-0022).

### D5. Evolution

The breaking changes are:

- adding a derived attribute to a type that the stored schema has, required or optional;
- making an attribute derived;
- changing a derived attribute's expression.

Making a derived attribute plain is compatible. Removing a derived attribute is removing an attribute, which is breaking, as for any attribute.

### D6. Documents and payloads

- **Documents.** An attribute of a schema document may state `derived: "<expression>"`. The published JSON Schema has it, and problems are located at `/…/attributes/<name>/derived`.
- **The values schema.** The generated JSON Schema of a type's values lists derived attributes with `readOnly: true`.
- **The payload schema.** The generated schema for transaction payloads does not accept them in `create`, `set` or `replace` values. Required derived attributes are not required in creates and replaces.
- **The payload reader.** It accepts a payload that names a derived attribute, so that the engine reports `DERIVED_ATTRIBUTE_WRITTEN` at the write. When the payload was read, the problem is located at the value in the payload.

### D7. Generated Java types

- Builders of writes have no setter for a derived attribute, and creates never require it.
- Snapshot records and typed attribute references include it, and its Javadoc says it is derived and gives the expression.

### D8. The HR example and the reference domain

- **HR.** `Person` gains `fullName: { type: string, required: true, derived: "self.givenName + ' ' + self.familyName" }`. Tests read Dev's full name as "Dev …", using the given and family names of the page. Queries and agent stories that compose names may use it. The HR page's model table shows the attribute; the page test decides the form.
- **Reference domain.** Scenarios use test-only derived attributes, which the reference domain page lists:
  - `label` on Account: `self.number + ' ' + self.currency`.
  - `balanceInCents` (long) on Account: `int(self.balance * 100.0)`.
  - `birthYear` (int, optional) on Person: `has(self.birthDate) ? self.birthDate.getFullYear() : null`. If CEL's checker refuses the conditional with `null`, report it, and the scenario changes.
  - `balanceInBillions` (int) on Account: `int(self.balance) * 10000000`, which does not fit an `int` for a balance of 1,000.

## Risks / Trade-offs

- **Doubles:** derived decimals are only as exact as a double, as ADR-0031 accepts.
- **Breaking changes:** adding a derived attribute to a type that has data is refused until migrations exist; the guide says to add it with the type.
- **Renaming a problem code:** `CHECKS_UNAVAILABLE` is renamed before any release; the checks spec is modified with it.
