# Design

## Context

ADR-0030 decides checks in CEL over one version's values, compiled at registration and run at commit, by an optional module `cairn-cel` on `dev.cel:cel` (0.14.0). That library brings Guava, protobuf-java, the ANTLR runtime, threeten-extra, SnakeYAML and RE2/J, so `cairn-core` defines an interface and finds the module through `ServiceLoader`, as it finds rules on request.

## Goals / Non-Goals

**Goals:**
- Checks declared in definitions and documents, compiled at registration with located problems, and run at commit in every kind of store.
- Schema evolution rules for checks.
- The module, and its use in the agent server and the HR example.

**Non-goals:** see the proposal.

## Decisions

### D1. The definition

In `cairn-api`, package `schema`:

- `record Check(String name, String expression, Optional<String> message)`.
  - The name follows the rule-name syntax: lowercase ASCII letters, digits and hyphens, starting with a letter, at most 128 characters.
  - The expression is not blank and has at most 4,000 characters.
- `EntityTypeDefinition` and `RelationshipTypeDefinition` gain `List<Check> checks`, as their last component.
  - Each keeps a constructor without it, as `Receipt` did with `keys`.
  - Each builder gains `check(name, expression)` and `check(name, expression, message)`.
- `EntityType` and `RelationshipType` in the resolved schema report `checks()`, which they declare, and `effectiveChecks()`, which they declare or inherit. These mirror the reporting of rules.
- Validation problems (a new `ProblemCode` each):
  - `INVALID_CHECK_NAME`, the name rule.
  - `DUPLICATE_CHECK`: a name declared twice on a type or across its hierarchy.
  - `INVALID_CHECK`: the expression does not compile.
  - `CHECKS_UNAVAILABLE`: no expression language.
  - The path is `<Type>.<check-name>`, as for rules.
- Problems at commit (also `ProblemCode`):
  - `CHECK_VIOLATED`, with the check's name, and its message or a default naming the expression.
  - `CHECK_FAILED`, with the check's name and the evaluation error.
  - Both have the path of the write (`@write.<n>`) or the ID, like other value problems.
  - Both have explanations, as every code does.

### D2. The expression interface

`cairn-api` gets a package `io.github.enricoag1982.cairn.expressions`, exported, with these interfaces:

- `ExpressionLanguage`, which has:
  - `String name()`, `"cel"`;
  - `CompiledCheck compile(String expression, Map<String, ValueType> attributes)`, which throws `CheckCompilationException` (message, and 1-based line and column in the expression, when known).
- `CompiledCheck`, with `boolean evaluate(Map<String, Value> values)`, which throws `CheckEvaluationException`.

`cairn-core`'s module declares `uses ExpressionLanguage`. `StoreSettings.Builder.build()` and the `SchemaRegistries.inMemory` overloads load the first one that `ServiceLoader` finds; `StoreSettings.Builder.expressionLanguage(...)` sets one explicitly and `noExpressionLanguage()` sets none, which tests use.

The registry compiles each check of a valid definition against the attributes of its type, inherited ones included, for each concrete type that inherits it. It keeps the compiled checks with the compiled schema. A store opened on a recorded schema compiles the checks again; without a language, writes to types with checks are rejected with `CHECKS_UNAVAILABLE` at the write.

### D3. `cairn-cel`

The subproject `cairn-cel` depends on `cairn-api` (and on `cairn-core` only in tests) and on `dev.cel:cel` from the version catalog. It provides `ExpressionLanguage` through `META-INF/services`. It runs on the class path, without `module-info`, because CEL's jars have no module names and split packages; `cairn-api` and `cairn-core` on the module path still find it.

How `self` is declared:

- `self` is a struct type, `cairn.Self`, with one field per attribute of the type and its CEL type, through a type provider, without protobuf messages. The checker finds unknown attributes and type errors at registration, with their positions; at run time `self` is a map of the values that are present, so `has(self.x)` works and reading an absent one is an evaluation error.
- The result type must be `bool`.
- Mixed `int` and `double` operands are rejected, as CEL does by default: decimals compare with `0.0`, not `0`.

Values passed in:

| Cairn | CEL |
| --- | --- |
| string, allowed values | string |
| boolean | bool |
| int and long | int |
| decimal | double |
| date | timestamp at 00:00 UTC |
| instant | timestamp |

Limits:

- CEL for Java has no runtime cost tracking, so evaluation counts the steps it evaluates and stops after 10,000, as an evaluation error. A plain check takes a few steps.
- Compilation rejects expressions with a nesting depth above CEL's default.

The code generator validates documents with a schema registry, so a build whose documents declare checks puts `cairn-cel` on the generator's class path (`cairnCodegen`).

### D4. At commit

`Validation` runs the effective checks of a type on every segment of a timeline that the transaction writes or that a cascade changes, after attribute constraints and before rules. It uses that segment's values: absent optional attributes are absent, as in the map.

A false result is `CHECK_VIOLATED`, and an exception is `CHECK_FAILED`. The problem names the segment's start when the write covers several segments. Checks do not record reads and cause no re-checks. Dry runs report the same problems.

### D5. Evolution

The breaking changes are:

- adding a check to a type that the stored schema has;
- changing a check's expression;
- changing the type a check is declared on, in a way that adds it to existing types.

The compatible changes are removing a check, and adding, changing or removing its message.

### D6. Documents

A type definition gains `checks`, a mapping from check names to `{ expression, message? }`. The published JSON Schema has the same form.

Reading locates `INVALID_CHECK` problems at the check's `expression` key, adding the line and column within the expression when the library gives them. Writing writes checks in name order.

### D7. The HR example and the reference domain

- **HR.** The HR schema document declares on `LeaveRequest` the check `leave-dates-in-order` with `self.lastDay >= self.firstDay` and the message "The last day is before the first day.", and the Java rule is removed. Its tests expect `CHECK_VIOLATED` with that name. `cairn-example-hr` uses `cairn-cel` at test runtime.
- **Reference domain.** Scenario tests use the test-only check `balance-not-negative` on Account, `self.balance >= 0.0`, added to the model in the tests that need it. The reference domain page lists it with the test-only rules.

## Risks / Trade-offs

- **Doubles:** decimals as doubles lose exactness, as ADR-0030 accepts. The guide says to keep exact comparisons in Java rules.
- **Dependency versions:** the CEL library's protobuf and Guava versions can clash with an application's own. The module is optional, and the guide says so.
- **No language at reopen:** a store reopened without `cairn-cel` keeps its data readable, but refuses writes to types with checks.
