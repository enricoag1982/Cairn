# Tasks

## 1. API (`cairn-api`)

- [x] 1.1 Add `StringLiteral` and `BooleanLiteral` to `Literal`, with `Literal.of(String)` and `Literal.of(boolean)` (D2)
- [x] 1.2 Add the new components to `AttributeDefinition`, `EntityTypeDefinition`, `TraitDefinition` and `RelationshipTypeDefinition`, add `RuleReference`, and extend the builders (D1)
- [x] 1.3 Add `RuleDescriptor` and `RuleCode` (D3)
- [x] 1.4 Add `expected`, `found` and `hint` to `Problem`, keep the three-argument constructor, and give every `ProblemCode` an explanation, including the new codes (D4, D5)
- [x] 1.5 Add the new properties to `Attribute`, `EntityType`, `Trait` and `RelationshipType`, with `rules()`, `effectiveRules()` and `EffectiveRule` (D8)
- [x] 1.6 Add `SchemaPlan`, its `Outcome`, `SchemaChange`, `SchemaRegistry.plan` and `SchemaRegistry.availableRules` (D3, D7)
- [x] 1.7 Update `DefinitionRecordsTest`, `SchemaDefinitionBuilderTest` and the `Definitions` test fixture for the new components; verify `./gradlew :cairn-api:build` passes

## 2. Engine (`cairn-core`)

- [x] 2.1 Create registries with available rules: `SchemaRegistries.inMemory(InstantSource, Collection<RuleDescriptor>)`, rejecting duplicates (D3)
- [x] 2.2 Validate string constraints, examples and rule references with the new problem codes (D4)
- [x] 2.3 Fill in expected, found and hint for the codes in D5, including the closest-name suggestion
- [x] 2.4 Carry the new properties into the resolved schema, with inherited rules (D8)
- [x] 2.5 Add compatibility rules for the new properties and hints on breaking changes, and compare allowed values and rules as sets in `Canonical` (D6)
- [x] 2.6 Implement `plan` and rebuild `register` on it (D7)

## 3. Reference domain

- [ ] 3.1 Add the string constraints and the rules table to `docs/reference/reference-domain.md` (D9)
- [ ] 3.2 Update `ReferenceModel` and `ReferenceModelTest`, add `ReferenceModel.rules()`, and create registries with these rules wherever tests register the reference model

## 4. Scenario tests

- [ ] 4.1 One test per new or changed scenario of `type-model`, named `Scenario: <name>`
- [ ] 4.2 One test per new or changed scenario of `schema-evolution`, named `Scenario: <name>`
- [ ] 4.3 Tests that every `ProblemCode` has an explanation, and for each new problem code; verify `./gradlew build` passes

## 5. Documentation

- [x] 5.1 Add `Plan` and `Available rule` to `docs/glossary.md`, and update the `Problem` and `Rule` entries
- [ ] 5.2 Update the building blocks in `docs/architecture/README.md` and bump `last-reviewed`
- [ ] 5.3 Run `npm run check` and `./gradlew build`, then a drift review by the `docs-reviewer` subagent, and fix its findings
