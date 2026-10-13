package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.assertSingleProblem;
import static io.github.enricoag1982.cairn.core.Results.locatedAt;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.core.Results.unchanged;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeRelationshipType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.plus;
import static io.github.enricoag1982.cairn.fixtures.Definitions.renamed;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withAttributes;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code schema-evolution} spec, one test each. */
class SchemaEvolutionScenariosTest {

    private final TestClock clock = new TestClock();
    private final SchemaRegistry registry = SchemaRegistries.inMemory(clock, ReferenceModel.rules());

    /** Version 1.1.0 of the reference model: Person gets the optional attribute email. */
    static SchemaDefinition withEmail() {
        return changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttribute(type, AttributeDefinition.builder("email", ValueType.STRING).build()));
    }

    static EntityTypeDefinition withAttribute(EntityTypeDefinition type, AttributeDefinition attribute) {
        List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
        attributes.add(attribute);
        return withAttributes(type, attributes);
    }

    private RegistrationResult register(String version, SchemaDefinition definition) {
        clock.advance(Duration.ofMinutes(1));
        return registry.register(ReferenceModel.origin(version), definition);
    }

    // ------------------------------------------------------------------ registering a schema

    @Test
    @DisplayName("Scenario: First registration")
    void firstRegistration() {
        RegistrationResult.Applied result = applied(register("1.0.0", ReferenceModel.definition()));

        assertThat(result.basis().value()).isGreaterThan(0);
        assertThat(result.transactionTime()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
        Schema schema = result.schema();
        List<Long> ids = new ArrayList<>();
        schema.entityTypes().forEach(type -> ids.add(type.id().value()));
        schema.traits().forEach(trait -> ids.add(trait.id().value()));
        schema.relationshipTypes().forEach(type -> ids.add(type.id().value()));
        declaredAttributes(schema).values().forEach(id -> ids.add(id.value()));
        assertThat(schema.entityTypes()).hasSize(6);
        assertThat(schema.traits()).hasSize(1);
        assertThat(schema.relationshipTypes()).hasSize(5);
        assertThat(declaredAttributes(schema)).hasSize(12);
        assertThat(ids).doesNotHaveDuplicates().allMatch(id -> id > 0);
        assertThat(registry.history()).singleElement()
                .satisfies(transaction -> assertThat(transaction.origin()).isEqualTo(ReferenceModel.origin("1.0.0")));
    }

    @Test
    @DisplayName("Scenario: Malformed origin")
    void malformedOrigin() {
        List<Problem> problems = rejected(registry.register(new Origin("reference-model", "1.x"), ReferenceModel.definition()));

        assertSingleProblem(problems, ProblemCode.INVALID_ORIGIN, "@origin");
        assertThat(registry.history()).isEmpty();
    }

    @Test
    void aMalformedOriginIsReportedTogetherWithTheDefinitionsProblems() {
        SchemaDefinition broken = plus(ReferenceModel.definition(),
                SchemaDefinition.builder().entityType("Lead", e -> e.supertype("Prospect")).build());

        List<Problem> problems = rejected(registry.register(new Origin("reference-model", "1.x"), broken));

        assertThat(problems).map(Problem::code)
                .containsExactlyInAnyOrder(ProblemCode.INVALID_ORIGIN, ProblemCode.UNKNOWN_REFERENCE);
    }

    @Test
    void anEntityTypeNamedOriginIsNotMistakenForTheOrigin() {
        SchemaDefinition broken = plus(ReferenceModel.definition(),
                SchemaDefinition.builder().entityType("origin", e -> e.supertype("Prospect")).build());

        List<Problem> problems = rejected(register("1.0.0", broken));

        assertThat(problems).singleElement().satisfies(problem -> assertThat(problem.path()).isEqualTo("origin"));
        assertThat(problems.getFirst().code()).isEqualTo(ProblemCode.UNKNOWN_REFERENCE);
    }

    @Test
    void malformedOriginName() {
        List<Problem> problems = rejected(registry.register(new Origin("reference model", "1.0.0"), ReferenceModel.definition()));

        assertSingleProblem(problems, ProblemCode.INVALID_ORIGIN, "@origin");
    }

    @Test
    @DisplayName("Scenario: Invalid definition")
    void invalidDefinition() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Lead", e -> e.supertype("Prospect"))
                .relationshipType("Refers", r -> r.from("Lead").to("Nobody").inverse("referredBy"))
                .build());

        List<Problem> problems = rejected(register("1.0.0", definition));

        assertThat(problems).map(Problem::code).containsOnly(ProblemCode.UNKNOWN_REFERENCE).hasSize(2);
        assertThat(registry.history()).isEmpty();
    }

    // ------------------------------------------------------------------ identifiers

    @Test
    @DisplayName("Scenario: Identifiers survive later registrations")
    void identifiersSurviveLaterRegistrations() {
        Schema first = applied(register("1.0.0", ReferenceModel.definition())).schema();
        Schema second = applied(register("1.1.0", withEmail())).schema();

        assertThat(typeIds(second)).containsAllEntriesOf(typeIds(first));
        Map<String, AttributeId> before = declaredAttributes(first);
        Map<String, AttributeId> after = declaredAttributes(second);
        assertThat(after).containsAllEntriesOf(before);
        assertThat(after.get("Person.email")).isNotNull();
        assertThat(before.values()).doesNotContain(after.get("Person.email"));
        assertThat(typeIds(first).values().stream().map(TypeId::value)).doesNotContain(after.get("Person.email").value());
    }

    // ------------------------------------------------------------------ unchanged registrations

    @Test
    @DisplayName("Scenario: Registering the same model twice")
    void registeringTheSameModelTwice() {
        applied(register("1.0.0", ReferenceModel.definition()));

        unchanged(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.history()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Same model in a different order")
    void sameModelInADifferentOrder() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition reordered = new SchemaDefinition(
                ReferenceModel.definition().entityTypes().reversed(),
                ReferenceModel.definition().traits(),
                ReferenceModel.definition().relationshipTypes().reversed());

        unchanged(register("1.0.0", reordered));
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("Scenario: Reading an earlier version")
    void readingAnEarlierVersion() {
        Basis b1 = applied(register("1.0.0", ReferenceModel.definition())).basis();
        Basis b2 = applied(register("1.1.0", withEmail())).basis();

        assertThat(b2).isGreaterThan(b1);
        assertThat(registry.asOf(b1).entityType("Person").orElseThrow().attribute("email")).isEmpty();
        assertThat(registry.asOf(b2).entityType("Person").orElseThrow().attribute("email")).isPresent();
        assertThat(registry.asOf(registry.history().getFirst().transactionTime())
                .entityType("Person").orElseThrow().attribute("email")).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Before any registration")
    void beforeAnyRegistration() {
        applied(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.asOf(TestClock.START).isEmpty()).isTrue();
        assertThat(registry.asOf(Basis.NONE).entityTypes()).isEmpty();
    }

    // ------------------------------------------------------------------ origins

    @Test
    @DisplayName("Scenario: Referencing another origin's types")
    void referencingAnotherOriginsTypes() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition crm = SchemaDefinition.builder().entityType("Lead", e -> e.supertype("Party")).build();

        Schema schema = applied(registry.register(new Origin("crm-model", "1.0.0"), crm)).schema();

        assertThat(schema.entityType("Lead").orElseThrow().origin()).isEqualTo(new Origin("crm-model", "1.0.0"));
        assertThat(schema.entityType("Party").orElseThrow().origin().name()).isEqualTo("reference-model");
        assertThat(schema.entityType("Party").orElseThrow().subtypes()).contains("Lead");
        assertThat(schema.origins()).map(Origin::name).containsExactly("crm-model", "reference-model");
    }

    @Test
    @DisplayName("Scenario: Redefining another origin's type")
    void redefiningAnotherOriginsType() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition crm = SchemaDefinition.builder()
                .entityType("Lead")
                .entityType("Party", e -> e.abstractType())
                .build();

        List<Problem> problems = rejected(registry.register(new Origin("crm-model", "1.0.0"), crm));

        assertSingleProblem(problems, ProblemCode.OWNED_BY_OTHER_ORIGIN, "Party");
        assertThat(registry.history()).hasSize(1);
    }

    // ------------------------------------------------------------------ versions

    @Test
    @DisplayName("Scenario: Rolling back to an earlier release")
    void rollingBackToAnEarlierRelease() {
        applied(register("1.0.0", ReferenceModel.definition()));
        applied(register("1.1.0", withEmail()));

        unchanged(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.current().entityType("Person").orElseThrow().attribute("email")).isPresent();
        assertThat(registry.history()).hasSize(2);
    }

    @Test
    @DisplayName("Scenario: Lower version that is not a predecessor")
    void lowerVersionThatIsNotAPredecessor() {
        applied(register("1.0.0", ReferenceModel.definition()));
        applied(register("1.1.0", withEmail()));
        SchemaDefinition withNickname = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttribute(type, AttributeDefinition.builder("nickname", ValueType.STRING).build()));

        List<Problem> problems = rejected(register("1.0.5", withNickname));

        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
            assertThat(problem.path()).isEqualTo("Person.nickname");
            assertThat(problem.message()).contains("removed");
        });
    }

    @Test
    @DisplayName("Scenario: Changed definition with the same version")
    void changedDefinitionWithTheSameVersion() {
        applied(register("1.0.0", ReferenceModel.definition()));

        List<Problem> problems = rejected(register("1.0.0", withEmail()));

        assertSingleProblem(problems, ProblemCode.VERSION_NOT_INCREASED, "@origin");
        assertThat(problems.getFirst().message()).contains("reference-model", "1.0.0");
    }

    @Test
    void aVersionWithFewerNumbersIsTheSameVersion() {
        applied(register("1.0.0", ReferenceModel.definition()));

        assertSingleProblem(rejected(register("1.0", withEmail())), ProblemCode.VERSION_NOT_INCREASED, "@origin");
    }

    @Test
    void decimalBoundsCompareByValue() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition sameShare = changeRelationshipType(ReferenceModel.definition(), "Owns", type ->
                new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                        type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                        type.supertype(), List.of(AttributeDefinition.builder("share", ValueType.DECIMAL).required()
                                .range(io.github.enricoag1982.cairn.schema.Range.between(
                                        io.github.enricoag1982.cairn.schema.Bound.exclusive(
                                                io.github.enricoag1982.cairn.schema.Literal.of(java.math.BigDecimal.ZERO)),
                                        io.github.enricoag1982.cairn.schema.Bound.inclusive(
                                                io.github.enricoag1982.cairn.schema.Literal.of(new java.math.BigDecimal("100.00")))))
                                .build()),
                        type.aliases(), type.deprecated(), type.description(), type.rules()));

        assertThat(registry.register(ReferenceModel.origin("1.0.0"), sameShare))
                .isInstanceOf(RegistrationResult.Unchanged.class);
    }

    // ------------------------------------------------------------------ compatible changes

    @Test
    @DisplayName("Scenario: Adding an optional attribute")
    void addingAnOptionalAttribute() {
        applied(register("1.0.0", ReferenceModel.definition()));

        applied(register("1.1.0", withEmail()));
    }

    @Test
    @DisplayName("Scenario: Widening a value type")
    void wideningAValueType() {
        SchemaDefinition withHeadcount = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                withAttribute(type, AttributeDefinition.builder("headcount", ValueType.INT).build()));
        Attribute before = applied(register("1.0.0", withHeadcount)).schema()
                .entityType("Organization").orElseThrow().attribute("headcount").orElseThrow();

        SchemaDefinition widened = changeEntityType(withHeadcount, "Organization", type ->
                changeAttribute(type, "headcount", attribute -> AttributeDefinition.builder("headcount", ValueType.LONG).build()));
        Attribute after = applied(register("1.1.0", widened)).schema()
                .entityType("Organization").orElseThrow().attribute("headcount").orElseThrow();

        assertThat(after.valueType()).isEqualTo(ValueType.LONG);
        assertThat(after.id()).isEqualTo(before.id());
    }

    @Test
    @DisplayName("Scenario: Relaxing a multiplicity")
    void relaxingAMultiplicity() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition relaxed = changeRelationshipType(ReferenceModel.definition(), "HeldBy", type ->
                withTargetsPerSource(type, Multiplicity.ONE_OR_MORE));

        applied(register("1.1.0", relaxed));
    }

    @Test
    @DisplayName("Scenario: Changing a description")
    void changingADescription() {
        applied(register("1.0.0", changeEntityType(ReferenceModel.definition(), "Organization",
                type -> describedAs(type, "A company"))));

        Schema after = applied(register("1.1.0", changeEntityType(ReferenceModel.definition(), "Organization",
                type -> describedAs(type, "A company or another legal entity")))).schema();

        assertThat(after.entityType("Organization").orElseThrow().description())
                .contains("A company or another legal entity");
    }

    @Test
    @DisplayName("Scenario: Adding an allowed value")
    void addingAnAllowedValue() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withPrivate = changeEntityType(ReferenceModel.definition(), "CustomerRole", type ->
                changeAttribute(type, "segment", attribute -> AttributeDefinition.builder("segment", ValueType.STRING)
                        .required().allowedValues("Corporate", "Retail", "Private").build()));

        Schema after = applied(register("1.1.0", withPrivate)).schema();

        assertThat(after.entityType("CustomerRole").orElseThrow().attribute("segment").orElseThrow().allowedValues())
                .containsExactlyInAnyOrder("Corporate", "Retail", "Private");
    }

    @Test
    @DisplayName("Scenario: Removing a rule")
    void removingARule() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withoutRule = changeEntityType(ReferenceModel.definition(), "Organization",
                type -> withRules(type, List.of()));

        Schema after = applied(register("1.1.0", withoutRule)).schema();

        assertThat(after.entityType("Organization").orElseThrow().rules()).isEmpty();
    }

    // ------------------------------------------------------------------ breaking changes

    @Test
    @DisplayName("Scenario: Removing an attribute")
    void removingAnAttribute() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withoutBirthDate = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttributes(type, List.of()));

        List<Problem> problems = rejected(register("1.1.0", withoutBirthDate));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.birthDate");
        assertThat(problems.getFirst().hint())
                .hasValueSatisfying(hint -> assertThat(hint).contains("deprecated", "alias"));
    }

    @Test
    @DisplayName("Scenario: Renaming without an alias")
    void renamingWithoutAnAlias() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition renamedLei = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                changeAttribute(type, "lei", attribute -> renamed(attribute, "legalEntityId")));

        List<Problem> problems = rejected(register("1.1.0", renamedLei));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization.lei");
        assertThat(problems.getFirst().message()).contains("removed");
    }

    @Test
    @DisplayName("Scenario: Making an attribute required")
    void makingAnAttributeRequired() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition requiredBirthDate = changeEntityType(ReferenceModel.definition(), "Person", type ->
                changeAttribute(type, "birthDate", attribute -> AttributeDefinition.builder("birthDate", ValueType.DATE)
                        .required().build()));

        List<Problem> problems = rejected(register("1.1.0", requiredBirthDate));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.birthDate");
    }

    @Test
    @DisplayName("Scenario: Including a trait with a unique attribute")
    void includingATraitWithAUniqueAttribute() {
        SchemaDefinition withCoded = changeEntityType(plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .trait("Coded", t -> t.attribute("code", ValueType.STRING, a -> a.unique())).build()),
                "Account", type -> includes(type, "Coded"));
        applied(register("1.0.0", withCoded));

        List<Problem> problems = rejected(register("1.1.0", changeEntityType(withCoded, "CustomerRole",
                type -> includes(type, "Coded"))));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Coded");
        assertThat(problems.getFirst().path()).isEqualTo("Coded.code");
    }

    @Test
    @DisplayName("Scenario: Widening a required relationship end")
    void wideningARequiredRelationshipEnd() {
        SchemaDefinition withManages = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("Manages", r -> r.from("Person").to("Organization")
                        .targetsPerSource(Multiplicity.EXACTLY_ONE).inverse("managedBy"))
                .build());
        applied(register("1.0.0", withManages));
        SchemaDefinition widened = changeRelationshipType(withManages, "Manages", type ->
                new RelationshipTypeDefinition(type.name(), "Party", type.target(), type.targetsPerSource(),
                        type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                        type.supertype(), type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules()));

        List<Problem> problems = rejected(register("1.1.0", widened));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Manages");
        assertThat(problems.getFirst().message()).contains("Organization");
    }

    @Test
    @DisplayName("Scenario: Removing an allowed value")
    void removingAnAllowedValue() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition onlyCorporate = changeEntityType(ReferenceModel.definition(), "CustomerRole", type ->
                changeAttribute(type, "segment", attribute -> AttributeDefinition.builder("segment", ValueType.STRING)
                        .required().allowedValues("Corporate").build()));

        List<Problem> problems = rejected(register("1.1.0", onlyCorporate));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "CustomerRole");
        assertThat(problems.getFirst().path()).isEqualTo("CustomerRole.segment");
    }

    @Test
    @DisplayName("Scenario: Adding a rule to an existing type")
    void addingARuleToAnExistingType() {
        RuleDescriptor birthDateInPast = new RuleDescriptor("birth-date-in-past", 1,
                "The birth date is before today.", "Person", List.of());
        List<RuleDescriptor> rules = new ArrayList<>(ReferenceModel.rules());
        rules.add(birthDateInPast);
        SchemaRegistry withRule = SchemaRegistries.inMemory(clock, rules);
        applied(withRule.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));
        SchemaDefinition namingIt = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withRules(type, List.of(new RuleReference("birth-date-in-past", 1))));

        List<Problem> problems = rejected(withRule.register(ReferenceModel.origin("1.1.0"), namingIt));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.birth-date-in-past");
    }

    @Test
    @DisplayName("Scenario: Several breaking changes")
    void severalBreakingChanges() {
        Schema before = applied(register("1.0.0", ReferenceModel.definition())).schema();
        SchemaDefinition changed = changeEntityType(
                changeEntityType(ReferenceModel.definition(), "Person", type -> withAttributes(type, List.of())),
                "Organization", type -> new EntityTypeDefinition(type.name(), true, type.supertype(), type.traits(),
                        type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules()));

        List<Problem> problems = rejected(register("1.1.0", changed));

        assertThat(problems).hasSize(2).allMatch(problem -> problem.code() == ProblemCode.BREAKING_CHANGE);
        assertThat(problems).anyMatch(problem -> problem.path().equals("Person.birthDate"));
        assertThat(problems).anyMatch(problem -> locatedAt(problem, "Organization"));
        assertThat(registry.current()).isSameAs(before);
    }

    // ------------------------------------------------------------------ planning

    @Test
    @DisplayName("Scenario: Plan of a compatible change")
    void planOfACompatibleChange() {
        applied(register("1.0.0", ReferenceModel.definition()));
        Schema stored = registry.current();

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"), withEmail());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
        });
        assertThat(registry.history()).hasSize(1);
        assertThat(registry.current()).isSameAs(stored);
    }

    @Test
    @DisplayName("Scenario: Plan of a breaking change")
    void planOfABreakingChange() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withoutBirthDate = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withAttributes(type, List.of()));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"), withoutBirthDate);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.birthDate");
            assertThat(change.compatible()).isFalse();
        });
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
            assertThat(problem.path()).isEqualTo("Person.birthDate");
        });
        assertThat(registry.history()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Plan of an unchanged registration")
    void planOfAnUnchangedRegistration() {
        applied(register("1.0.0", ReferenceModel.definition()));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.UNCHANGED);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.problems()).isEmpty();
        assertThat(registry.history()).hasSize(1);
    }

    // ------------------------------------------------------------------ aliases

    @Test
    @DisplayName("Scenario: Looking up an old name")
    void lookingUpAnOldName() {
        AttributeId leiId = applied(register("1.0.0", ReferenceModel.definition())).schema()
                .entityType("Organization").orElseThrow().attribute("lei").orElseThrow().id();
        SchemaDefinition renamedLei = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                changeAttribute(type, "lei", attribute -> renamed(attribute, "legalEntityId", "lei")));

        Schema schema = applied(register("1.2.0", renamedLei)).schema();

        Attribute found = schema.entityType("Organization").orElseThrow().attribute("lei").orElseThrow();
        assertThat(found.name()).isEqualTo("legalEntityId");
        assertThat(found.id()).isEqualTo(leiId);
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, TypeId> typeIds(Schema schema) {
        Map<String, TypeId> ids = new HashMap<>();
        schema.entityTypes().forEach(type -> ids.put(type.name(), type.id()));
        schema.traits().forEach(trait -> ids.put(trait.name(), trait.id()));
        schema.relationshipTypes().forEach(type -> ids.put(type.name(), type.id()));
        return ids;
    }

    /** Attribute identifiers by declaring element and attribute name, such as {@code Person.email}. */
    static Map<String, AttributeId> declaredAttributes(Schema schema) {
        Map<String, AttributeId> ids = new HashMap<>();
        List<Attribute> all = new ArrayList<>();
        schema.entityTypes().forEach(type -> all.addAll(type.attributes()));
        schema.traits().forEach(trait -> all.addAll(trait.attributes()));
        schema.relationshipTypes().forEach(type -> all.addAll(type.attributes()));
        all.forEach(attribute -> ids.put(attribute.declaredBy() + "." + attribute.name(), attribute.id()));
        return ids;
    }

    static EntityTypeDefinition describedAs(EntityTypeDefinition type, String description) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                type.attributes(), type.aliases(), type.deprecated(), Optional.of(description), type.rules());
    }

    static EntityTypeDefinition withRules(EntityTypeDefinition type, List<RuleReference> rules) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), rules);
    }

    static EntityTypeDefinition includes(EntityTypeDefinition type, String trait) {
        List<String> traits = new ArrayList<>(type.traits());
        traits.add(trait);
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), traits, type.attributes(),
                type.aliases(), type.deprecated(), type.description(), type.rules());
    }

    static RelationshipTypeDefinition withTargetsPerSource(RelationshipTypeDefinition type, Multiplicity value) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), value, type.sourcesPerTarget(),
                type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules());
    }
}
