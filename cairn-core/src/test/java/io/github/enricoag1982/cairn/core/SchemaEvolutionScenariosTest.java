package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.assertSingleProblem;
import static io.github.enricoag1982.cairn.core.Results.locatedAt;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.core.Results.unchanged;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeRelationshipType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeTrait;
import static io.github.enricoag1982.cairn.fixtures.Definitions.openedBy;
import static io.github.enricoag1982.cairn.fixtures.Definitions.plus;
import static io.github.enricoag1982.cairn.fixtures.Definitions.renamed;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withAttributes;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
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
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
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

    @Test
    @DisplayName("Scenario: Same allowed values in a different order")
    void sameAllowedValuesInADifferentOrder() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition reordered = changeEntityType(ReferenceModel.definition(), "CustomerRole", type ->
                changeAttribute(type, "segment", attribute -> AttributeDefinition.builder("segment", ValueType.STRING)
                        .required().allowedValues("Retail", "Corporate").build()));

        unchanged(register("1.0.0", reordered));

        assertThat(registry.history()).hasSize(1);
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
                                                io.github.enricoag1982.cairn.schema.Value.of(java.math.BigDecimal.ZERO)),
                                        io.github.enricoag1982.cairn.schema.Bound.inclusive(
                                                io.github.enricoag1982.cairn.schema.Value.of(new java.math.BigDecimal("100.00")))))
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

    @Test
    @DisplayName("Scenario: Removing a check")
    void removingACheck() {
        applied(register("1.0.0", withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                "self.balance >= 0.0", null)));

        Schema after = applied(register("1.1.0", ReferenceModel.definition())).schema();

        assertThat(after.entityType("Account").orElseThrow().checks()).isEmpty();
        assertThat(after.entityType("Account").orElseThrow().effectiveChecks()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Making a derived attribute plain")
    void makingADerivedAttributePlain() {
        Schema before = applied(register("1.0.0", withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
                ValueType.STRING, false, "self.number + ' ' + self.currency"))).schema();
        SchemaDefinition plain = changeEntityType(ReferenceModel.definition(), "Account", type ->
                withAttribute(type, AttributeDefinition.builder("label", ValueType.STRING).build()));

        Schema after = applied(register("1.1.0", plain)).schema();

        Attribute labelBefore = before.entityType("Account").orElseThrow().attributes().stream()
                .filter(attribute -> attribute.name().equals("label")).findFirst().orElseThrow();
        Attribute labelAfter = after.entityType("Account").orElseThrow().attributes().stream()
                .filter(attribute -> attribute.name().equals("label")).findFirst().orElseThrow();
        assertThat(labelBefore.derived()).isPresent();
        assertThat(labelAfter.derived()).isEmpty();
        assertThat(labelAfter.id()).as("the attribute keeps its identifier, and so its stored values")
                .isEqualTo(labelBefore.id());
    }

    @Test
    @DisplayName("Scenario: Letting relationships outlive their targets")
    void lettingRelationshipsOutliveTheirTargets() {
        applied(register("1.0.0", plus(ReferenceModel.definition(), openedBy(false))));

        Schema after = applied(register("1.1.0", plus(ReferenceModel.definition(), openedBy(true)))).schema();

        assertThat(after.relationshipType("OpenedBy").orElseThrow().outlivesTarget()).isTrue();
        assertThat(registry.storedDefinition(ReferenceModel.origin("1.1.0").name()).orElseThrow().definition()
                .relationshipTypes()).filteredOn(type -> type.name().equals("OpenedBy")).singleElement()
                .satisfies(type -> assertThat(type.outlivesTarget()).isTrue());
    }

    @Test
    @DisplayName("Scenario: Classifying an existing attribute")
    void classifyingAnExistingAttribute() {
        Schema first = applied(register("1.0.0", ReferenceModel.definition())).schema();
        SchemaDefinition classified = withClassification(ReferenceModel.definition(), "Account", "balance", "finance");

        RegistrationResult.Applied result = applied(register("1.1.0", classified));

        Attribute before = first.entityType("Account").orElseThrow().attribute("balance").orElseThrow();
        Attribute after = result.schema().entityType("Account").orElseThrow().attribute("balance").orElseThrow();
        assertThat(before.classification()).isEmpty();
        assertThat(after.classification()).hasValue("finance");
        assertThat(after.id()).as("the attribute keeps its identifier, and so its stored values").isEqualTo(before.id());
        assertThat(registry.storedDefinition(ReferenceModel.origin("1.1.0").name()).orElseThrow().definition()
                .entityTypes()).filteredOn(type -> type.name().equals("Account")).singleElement()
                .satisfies(type -> assertThat(type.attributes()).filteredOn(attribute ->
                        attribute.name().equals("balance")).singleElement()
                        .satisfies(attribute -> assertThat(attribute.classification()).hasValue("finance")));
    }

    @Test
    void changingAndRemovingAClassificationAreCompatibleToo() {
        applied(register("1.0.0", withClassification(ReferenceModel.definition(), "Account", "balance", "finance")));

        Schema changed = applied(register("1.1.0", withClassification(ReferenceModel.definition(), "Account", "balance",
                "accounts"))).schema();
        Schema removed = applied(register("1.2.0", ReferenceModel.definition())).schema();

        assertThat(changed.entityType("Account").orElseThrow().attribute("balance").orElseThrow().classification())
                .hasValue("accounts");
        assertThat(removed.entityType("Account").orElseThrow().attribute("balance").orElseThrow().classification())
                .isEmpty();
    }

    @Test
    void aPlanListsAChangedClassificationAsACompatibleChangeAtTheAttribute() {
        applied(register("1.0.0", ReferenceModel.definition()));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"),
                withClassification(ReferenceModel.definition(), "Account", "balance", "finance"));

        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Account.balance");
            assertThat(change.compatible()).isTrue();
            assertThat(change.description()).contains("finance");
        });
    }

    @Test
    void aDerivedAttributeThatReadsAClassifiedAttributeCannotBeRegisteredWithoutItsClassification() {
        SchemaRegistry withLanguage = SchemaRegistries.inMemory(clock, ReferenceModel.rules(),
                Optional.of(ExpressionLanguages.discovered().orElseThrow()));
        SchemaDefinition cents = withDerivedAttribute(ReferenceModel.definition(), "Account", "balanceInCents",
                ValueType.LONG, false, "int(self.balance * 100.0)");
        assertThat(withLanguage.register(ReferenceModel.origin("1.0.0"), cents))
                .isInstanceOf(RegistrationResult.Applied.class);

        RegistrationResult result = withLanguage.register(ReferenceModel.origin("1.1.0"),
                withClassification(cents, "Account", "balance", "finance"));

        assertThat(rejected(result)).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
            assertThat(problem.path()).isEqualTo("Account.balanceInCents");
        });
        assertThat(withLanguage.current().entityType("Account").orElseThrow().attribute("balance").orElseThrow()
                .classification()).isEmpty();
    }

    @Test
    void addingATypeThatOutlivesItsTargetsIsCompatible() {
        applied(register("1.0.0", ReferenceModel.definition()));

        Schema after = applied(register("1.1.0", plus(ReferenceModel.definition(), openedBy(true)))).schema();

        assertThat(after.relationshipType("OpenedBy").orElseThrow().outlivesTarget()).isTrue();
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
    @DisplayName("Scenario: Adding a check to an existing type")
    void addingACheckToAnExistingType() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withCheck = withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                "self.balance >= 0.0", null);

        List<Problem> problems = rejected(register("1.1.0", withCheck));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.balance-not-negative");
        assertThat(registry.current().entityType("Account").orElseThrow().checks()).as("nothing changed").isEmpty();
    }

    @Test
    @DisplayName("Scenario: Adding a derived attribute to an existing type")
    void addingADerivedAttributeToAnExistingType() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withLabel = withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
                ValueType.STRING, false, "self.number + ' ' + self.currency");

        List<Problem> problems = rejected(register("1.1.0", withLabel));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.label");
        assertThat(registry.current().entityType("Account").orElseThrow().attributes()).as("nothing changed")
                .extracting(Attribute::name).doesNotContain("label");
    }

    @Test
    @DisplayName("Scenario: Relationships that no longer outlive their targets")
    void relationshipsThatNoLongerOutliveTheirTargets() {
        applied(register("1.0.0", plus(ReferenceModel.definition(), openedBy(true))));

        List<Problem> problems = rejected(register("1.1.0", plus(ReferenceModel.definition(), openedBy(false))));

        assertSingleProblem(problems, ProblemCode.BREAKING_CHANGE, "OpenedBy");
        assertThat(problems.getFirst().message()).contains("outlive their targets");
        assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("outlivesTarget"));
        assertThat(registry.current().relationshipType("OpenedBy").orElseThrow().outlivesTarget())
                .as("nothing changed").isTrue();
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

    @Test
    @DisplayName("Scenario: Plan of a first registration")
    void planOfAFirstRegistration() {
        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        SchemaDefinition model = ReferenceModel.definition();
        List<String> types = new ArrayList<>();
        model.entityTypes().forEach(type -> types.add(type.name()));
        model.traits().forEach(trait -> types.add(trait.name()));
        model.relationshipTypes().forEach(type -> types.add(type.name()));
        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.changes()).allMatch(SchemaChange::compatible);
        assertThat(plan.changes()).map(SchemaChange::path).containsExactlyInAnyOrderElementsOf(types);
        assertThat(registry.history()).isEmpty();
        assertThat(registry.current().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Plan of an invalid definition")
    void planOfAnInvalidDefinition() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition twoParties = plus(ReferenceModel.definition(),
                SchemaDefinition.builder().entityType("Party").build());

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"), twoParties);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_NAME);
            assertThat(problem.path()).isEqualTo("Party");
        });
        assertThat(plan.changes()).isEmpty();
        assertThat(registry.history()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Plan of a rollback")
    void planOfARollback() {
        applied(register("1.0.0", ReferenceModel.definition()));
        applied(register("1.1.0", withEmail()));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.0.0"), ReferenceModel.definition());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.UNCHANGED);
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
        });
        assertThat(registry.history()).hasSize(2);
    }

    @Test
    @DisplayName("Scenario: Plan of a changed definition with the same version")
    void planOfAChangedDefinitionWithTheSameVersion() {
        applied(register("1.0.0", ReferenceModel.definition()));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.0.0"), withEmail());

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.REJECT);
        assertThat(plan.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.VERSION_NOT_INCREASED));
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Person.email");
            assertThat(change.compatible()).isTrue();
        });
        assertThat(registry.history()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Plan of an attribute added to a trait")
    void planOfAnAttributeAddedToATrait() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withNickname = changeTrait(ReferenceModel.definition(), "Named", trait -> {
            List<AttributeDefinition> attributes = new ArrayList<>(trait.attributes());
            attributes.add(AttributeDefinition.builder("nickname", ValueType.STRING).build());
            return new TraitDefinition(trait.name(), attributes, trait.aliases(), trait.deprecated(),
                    trait.description());
        });

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"), withNickname);

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Named.nickname");
            assertThat(change.compatible()).isTrue();
        });
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

    // ------------------------------------------------------------------ stored definitions

    @Test
    @DisplayName("Scenario: Reading a stored definition")
    void readingAStoredDefinition() {
        applied(register("1.0.0", ReferenceModel.definition()));
        applied(register("1.1.0", withEmail()));

        assertThat(registry.storedDefinition("reference-model")).contains(
                new StoredDefinition(ReferenceModel.origin("1.1.0"), withEmail()));
    }

    @Test
    @DisplayName("Scenario: Unknown origin")
    void unknownOrigin() {
        assertThat(registry.storedDefinition("reference-model")).isEmpty();

        applied(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.storedDefinition("another-model")).isEmpty();
        assertThat(registry.storedDefinition("Reference-Model")).isEmpty();
    }

    @Test
    void aStoredDefinitionChangesOnlyWithAnAppliedRegistration() {
        applied(register("1.0.0", ReferenceModel.definition()));
        applied(register("1.1.0", withEmail()));
        StoredDefinition stored = registry.storedDefinition("reference-model").orElseThrow();

        rejected(register("1.1.0", ReferenceModel.definition()));
        unchanged(register("1.1.0", withEmail()));
        unchanged(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.storedDefinition("reference-model")).contains(stored);
    }

    @Test
    void everyOriginHasItsOwnStoredDefinition() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition crm = SchemaDefinition.builder().entityType("Lead").build();
        applied(registry.register(new Origin("crm", "2.0.0"), crm));

        assertThat(registry.storedDefinition("crm")).contains(new StoredDefinition(new Origin("crm", "2.0.0"), crm));
        assertThat(registry.storedDefinition("reference-model").orElseThrow().definition())
                .isEqualTo(ReferenceModel.definition());
    }

    // ------------------------------------------------------------------ who and why

    @Test
    @DisplayName("Scenario: An agent's schema change in the history")
    void anAgentsSchemaChangeInTheHistory() {
        applied(register("1.0.0", ReferenceModel.definition()));
        clock.advance(Duration.ofMinutes(1));

        applied(registry.register(ReferenceModel.origin("1.1.0"), withEmail(),
                new Submission("agent:modeler", Optional.empty(), Optional.of("Contact data"))));

        assertThat(registry.history()).hasSize(2);
        SchemaTransaction change = registry.history().getLast();
        assertThat(change.origin()).isEqualTo(ReferenceModel.origin("1.1.0"));
        assertThat(change.principal()).isEqualTo("agent:modeler");
        assertThat(change.reason()).contains("Contact data");
        assertThat(change.onBehalfOf()).isEmpty();
    }

    @Test
    void aRegistrationThatNamesNoPrincipalIsRecordedAsUnknown() {
        applied(register("1.0.0", ReferenceModel.definition()));

        assertThat(registry.history()).singleElement().satisfies(transaction -> {
            assertThat(transaction.principal()).isEqualTo("@unknown");
            assertThat(transaction.onBehalfOf()).isEmpty();
            assertThat(transaction.reason()).isEmpty();
        });
    }

    @Test
    void aSubmissionRecordsThePartyTheAgentActsFor() {
        applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition(),
                new Submission("agent:modeler", Optional.of("alice"), Optional.of("Initial model"))));

        assertThat(registry.history()).singleElement().satisfies(transaction -> {
            assertThat(transaction.principal()).isEqualTo("agent:modeler");
            assertThat(transaction.onBehalfOf()).contains("alice");
            assertThat(transaction.reason()).contains("Initial model");
        });
    }

    @Test
    void aRegistrationThatIsNotAppliedRecordsNothing() {
        applied(register("1.0.0", ReferenceModel.definition()));
        Submission agent = Submission.by("agent:modeler");

        unchanged(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition(), agent));
        rejected(registry.register(ReferenceModel.origin("1.0.1"), withoutBirthDate(), agent));

        assertThat(registry.history()).singleElement().satisfies(transaction ->
                assertThat(transaction.principal()).isEqualTo("@unknown"));
    }

    @Test
    void aRegistrationWithoutASubmissionIsRejected() {
        assertThatThrownBy(() -> registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static SchemaDefinition withoutBirthDate() {
        return changeEntityType(ReferenceModel.definition(), "Person", type -> withAttributes(type, List.of()));
    }

    // ------------------------------------------------------------------ a store's schema registry

    @Test
    @DisplayName("Scenario: Schema and data at one basis")
    void schemaAndDataAtOneBasis() {
        ReferenceStore reference = new ReferenceStore();
        Basis rd1 = reference.createParties().basis();
        clock.advance(Duration.ofMinutes(1));
        Basis withEmail = applied(reference.store.schema().register(ReferenceModel.origin("1.1.0"), withEmail()))
                .basis();

        var atRd1 = reference.store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(rd1));
        var latest = reference.view(on(2, 1));

        assertThat(withEmail).isGreaterThan(rd1);
        assertThat(atRd1.basis()).isEqualTo(rd1);
        assertThat(atRd1.schema().entityType("Person").orElseThrow().attribute("email")).isEmpty();
        assertThat(atRd1.entities("Person")).hasSize(1);
        assertThat(latest.basis()).isEqualTo(withEmail);
        assertThat(latest.schema().entityType("Person").orElseThrow().attribute("email")).isPresent();
        assertThat(latest.entities("Person")).hasSize(1);
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
