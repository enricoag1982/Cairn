package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.assertSingleProblem;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeRelationshipType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.openedBy;
import static io.github.enricoag1982.cairn.fixtures.Definitions.plus;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withOutlivesTarget;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code type-model} spec, one test each. */
class TypeModelScenariosTest {

    private static final Origin ORIGIN = ReferenceModel.origin("1.0.0");

    /** The rule {@code name-not-blank}, version 1, which the reference model does not name. */
    private static final RuleDescriptor NAME_NOT_BLANK = new RuleDescriptor("name-not-blank", 1,
            "The name has a visible character.", "Party",
            List.of(new RuleCode("blank-name", "The name is empty or has only white space.")));

    private final SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules());

    /** Creates a registry with the rules of the reference model and others. */
    private static SchemaRegistry registryWith(RuleDescriptor... others) {
        List<RuleDescriptor> rules = new ArrayList<>(ReferenceModel.rules());
        rules.addAll(List.of(others));
        return SchemaRegistries.inMemory(new TestClock(), rules);
    }

    private List<Problem> validate(SchemaDefinition definition) {
        return registry.validate(ORIGIN, definition);
    }

    private Schema register(SchemaDefinition definition) {
        return applied(registry.register(ORIGIN, definition)).schema();
    }

    // ------------------------------------------------------------------ schema definitions are plain data

    @Test
    @DisplayName("Scenario: Defining the reference model without classes")
    void definingTheReferenceModelWithoutClasses() {
        assertThat(validate(ReferenceModel.definition())).isEmpty();
    }

    // ------------------------------------------------------------------ entity types

    @Test
    @DisplayName("Scenario: Abstract supertype with concrete subtypes")
    void abstractSupertypeWithConcreteSubtypes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", ValueType.STRING, a -> a.required()))
                .entityType("Party", e -> e.abstractType().includes("Named"))
                .entityType("Person", e -> e.supertype("Party").attribute("birthDate", ValueType.DATE))
                .entityType("Organization", e -> e.supertype("Party"))
                .build();

        assertThat(validate(definition)).isEmpty();
        assertThat(register(definition).entityType("Person").orElseThrow().attributes())
                .map(Attribute::name).containsExactly("name", "birthDate");
    }

    @Test
    @DisplayName("Scenario: Supertype that is not an entity type")
    void supertypeThatIsNotAnEntityType() {
        SchemaDefinition withTraitSupertype = plus(ReferenceModel.definition(),
                SchemaDefinition.builder().entityType("Lead", e -> e.supertype("Named")).build());
        SchemaDefinition withRelationshipSupertype = plus(ReferenceModel.definition(),
                SchemaDefinition.builder().entityType("Lead", e -> e.supertype("Owns")).build());

        assertSingleProblem(validate(withTraitSupertype), ProblemCode.SUPERTYPE_NOT_ENTITY_TYPE, "Lead");
        assertSingleProblem(validate(withRelationshipSupertype), ProblemCode.SUPERTYPE_NOT_ENTITY_TYPE, "Lead");
    }

    @Test
    @DisplayName("Scenario: Cycle in supertypes")
    void cycleInSupertypes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("A", e -> e.supertype("B"))
                .entityType("B", e -> e.supertype("A"))
                .build();

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.SUPERTYPE_CYCLE, "A");
        assertThat(problems.getFirst().message()).contains("A", "B");
    }

    // ------------------------------------------------------------------ traits

    @Test
    @DisplayName("Scenario: Attributes come from a trait")
    void attributesComeFromATrait() {
        Schema schema = register(ReferenceModel.definition());

        for (String type : List.of("Party", "Person", "Organization")) {
            assertThat(schema.entityType(type).orElseThrow().attribute("name")).as(type)
                    .hasValueSatisfying(attribute -> assertThat(attribute.declaredBy()).isEqualTo("Named"));
        }
    }

    @Test
    @DisplayName("Scenario: Trait reached twice")
    void traitReachedTwice() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), List.of("Named"),
                        type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules()));

        assertThat(validate(definition)).isEmpty();
        assertThat(register(definition).entityType("Person").orElseThrow().attributes())
                .filteredOn(attribute -> attribute.name().equals("name")).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Trait as a relationship end")
    void traitAsARelationshipEnd() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("Mentions", r -> r.from("Named").to("Party").inverse("mentionedBy"))
                .build());

        assertSingleProblem(validate(definition), ProblemCode.RELATIONSHIP_END_NOT_ENTITY_TYPE, "Mentions");
    }

    // ------------------------------------------------------------------ attributes

    @Test
    @DisplayName("Scenario: Name declared twice in a hierarchy")
    void nameDeclaredTwiceInAHierarchy() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                withAttribute(type, AttributeDefinition.builder("name", ValueType.STRING).build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_ATTRIBUTE, "Organization");
        assertThat(problems.getFirst().message()).contains("trait Named", "entity type Organization");
    }

    @Test
    @DisplayName("Scenario: Attribute declared twice by one type")
    void attributeDeclaredTwiceByOneType() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                withAttribute(type, type.attributes().get(1)));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_ATTRIBUTE, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.currency");
    }

    @Test
    void anAliasMayNotRepeatAnotherAttributeOfTheSameType() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                withAttribute(type, AttributeDefinition.builder("code", ValueType.STRING).aliases("number").build()));

        assertSingleProblem(validate(definition), ProblemCode.DUPLICATE_ATTRIBUTE, "Account");
    }

    @Test
    void aMissingInverseNameIsReportedAtTheRelationshipType() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("Advises", r -> r.from("Person").to("Organization"))
                .build());

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_NAME, "Advises");
        assertThat(problems.getFirst().path()).isEqualTo("Advises");
    }

    @Test
    @DisplayName("Scenario: Same name from two traits")
    void sameNameFromTwoTraits() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Coded", t -> t.attribute("code", ValueType.STRING))
                .trait("Classified", t -> t.attribute("code", ValueType.STRING))
                .entityType("Product", e -> e.includes("Coded", "Classified"))
                .build();

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_ATTRIBUTE, "Product");
        assertThat(problems.getFirst().message()).contains("trait Coded", "trait Classified");
    }

    @Test
    @DisplayName("Scenario: Range on an unordered value type")
    void rangeOnAnUnorderedValueType() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                withAttribute(type, AttributeDefinition.builder("active", ValueType.BOOLEAN)
                        .range(Range.atLeast(Bound.inclusive(Value.of(0)))).build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.RANGE_NOT_ALLOWED, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.active");
    }

    @Test
    @DisplayName("Scenario: Valid range")
    void validRange() {
        assertThat(validate(ReferenceModel.definition())).isEmpty();

        Attribute share = register(ReferenceModel.definition()).relationshipType("Owns").orElseThrow()
                .attribute("share").orElseThrow();
        assertThat(share.valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(share.required()).isTrue();
        assertThat(share.range()).contains(Range.between(
                Bound.exclusive(Value.of(new BigDecimal("0"))), Bound.inclusive(Value.of(new BigDecimal("100")))));
    }

    @Test
    @DisplayName("Scenario: Valid string constraints")
    void validStringConstraints() {
        SchemaDefinition definition = ReferenceModel.definition();

        assertThat(validate(definition)).isEmpty();

        Schema schema = register(definition);
        assertThat(schema.entityType("Account").orElseThrow().attribute("currency").orElseThrow().pattern())
                .contains("[A-Z]{3}");
        assertThat(schema.entityType("CustomerRole").orElseThrow().attribute("segment").orElseThrow().allowedValues())
                .containsExactly("Corporate", "Retail");
    }

    @Test
    @DisplayName("Scenario: Pattern on a non-string attribute")
    void patternOnANonStringAttribute() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                changeAttribute(type, "balance", attribute -> AttributeDefinition.builder("balance", ValueType.DECIMAL)
                        .required().pattern("[0-9]+").build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.TEXT_CONSTRAINT_NOT_ALLOWED, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.balance");
    }

    @Test
    @DisplayName("Scenario: Invalid pattern")
    void invalidPattern() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                changeAttribute(type, "currency", attribute -> AttributeDefinition.builder("currency", ValueType.STRING)
                        .required().pattern("[A-Z").build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_PATTERN, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.currency");
    }

    @Test
    @DisplayName("Scenario: Pattern outside the portable syntax")
    void patternOutsideThePortableSyntax() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Account", type ->
                changeAttribute(type, "currency", attribute -> AttributeDefinition.builder("currency", ValueType.STRING)
                        .required().pattern("\\d{3}").build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_PATTERN, "Account");
        assertThat(problems.getFirst().path()).isEqualTo("Account.currency");
        assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("[0-9]", "\\d"));
    }

    @Test
    @DisplayName("Scenario: Minimum length above maximum length")
    void minimumLengthAboveMaximumLength() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "CustomerRole", type ->
                changeAttribute(type, "segment", attribute -> AttributeDefinition.builder("segment", ValueType.STRING)
                        .required().minLength(5).maxLength(3).build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_LENGTH, "CustomerRole");
        assertThat(problems.getFirst().path()).isEqualTo("CustomerRole.segment");
    }

    @Test
    @DisplayName("Scenario: Allowed value listed twice")
    void allowedValueListedTwice() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "CustomerRole", type ->
                changeAttribute(type, "segment", attribute -> AttributeDefinition.builder("segment", ValueType.STRING)
                        .required().allowedValues("Corporate", "Retail", "Corporate").build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_ALLOWED_VALUE, "CustomerRole");
        assertThat(problems.getFirst().path()).isEqualTo("CustomerRole.segment");
    }

    @Test
    @DisplayName("Scenario: Range bound of the wrong value type")
    void rangeBoundOfTheWrongValueType() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("opened", ValueType.DATE,
                        a -> a.range(Range.atLeast(Bound.inclusive(Value.of(1))))))
                .build();

        assertSingleProblem(validate(definition), ProblemCode.RANGE_LITERAL_MISMATCH, "Account");
    }

    @Test
    @DisplayName("Scenario: Empty range")
    void emptyRange() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("limit", ValueType.INT, a -> a.range(
                        Range.between(Bound.inclusive(Value.of(10)), Bound.exclusive(Value.of(10))))))
                .build();

        assertSingleProblem(validate(definition), ProblemCode.RANGE_EMPTY, "Account");
    }

    @Test
    void narrowerNumbersFitWiderAttributes() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("balance", ValueType.DECIMAL, a -> a.range(Range.atLeast(Bound.inclusive(Value.of(0L)))))
                        .attribute("count", ValueType.LONG, a -> a.range(Range.atMost(Bound.inclusive(Value.of(10))))))
                .build();

        assertThat(validate(definition)).isEmpty();
    }

    // ------------------------------------------------------------------ relationship types

    @Test
    @DisplayName("Scenario: Relationship to an abstract type")
    void relationshipToAnAbstractType() {
        assertThat(validate(ReferenceModel.definition())).isEmpty();

        var owns = register(ReferenceModel.definition()).relationshipType("Owns").orElseThrow();
        assertThat(owns.source()).isEqualTo("Organization");
        assertThat(owns.target()).isEqualTo("Party");
        assertThat(owns.targetsPerSource()).isEqualTo(Multiplicity.ZERO_OR_MORE);
        assertThat(owns.sourcesPerTarget()).isEqualTo(Multiplicity.ZERO_OR_MORE);
        assertThat(owns.inverseName()).isEqualTo("ownedBy");
    }

    @Test
    @DisplayName("Scenario: Exactly one target per source")
    void exactlyOneTargetPerSource() {
        var heldBy = register(ReferenceModel.definition()).relationshipType("HeldBy").orElseThrow();

        assertThat(heldBy.targetsPerSource().minimum()).isEqualTo(1);
        assertThat(heldBy.targetsPerSource().maximum()).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Composition with shared targets")
    void compositionWithSharedTargets() {
        SchemaDefinition definition = changeRelationshipType(ReferenceModel.definition(), "HasAddress", type ->
                withSourcesPerTarget(type, Multiplicity.ZERO_OR_MORE));

        assertSingleProblem(validate(definition), ProblemCode.COMPOSITION_MULTIPLICITY, "HasAddress");
    }

    @Test
    @DisplayName("Scenario: Symmetric relationship between different types")
    void symmetricRelationshipBetweenDifferentTypes() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("Advises", r -> r.from("Person").to("Organization").symmetric().inverse("advisedBy"))
                .build());

        assertSingleProblem(validate(definition), ProblemCode.SYMMETRIC_ENDS, "Advises");
    }

    @Test
    @DisplayName("Scenario: Relationship subtype with an unrelated target")
    void relationshipSubtypeWithAnUnrelatedTarget() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("OwnsAddress", r -> r.supertype("Owns").from("Organization").to("Address")
                        .inverse("addressOwnedBy"))
                .build());

        assertSingleProblem(validate(definition), ProblemCode.RELATIONSHIP_SUBTYPE_ENDS, "OwnsAddress");
    }

    @Test
    @DisplayName("Scenario: A relationship type that outlives its targets")
    void aRelationshipTypeThatOutlivesItsTargets() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), openedBy(true));

        assertThat(validate(definition)).isEmpty();
        Schema schema = register(definition);
        var openedBy = schema.relationshipType("OpenedBy").orElseThrow();
        assertThat(openedBy.outlivesTarget()).isTrue();
        assertThat(openedBy.source()).isEqualTo("Account");
        assertThat(openedBy.target()).isEqualTo("Person");
        assertThat(openedBy.targetsPerSource()).isEqualTo(Multiplicity.ZERO_OR_ONE);
        assertThat(openedBy.sourcesPerTarget()).isEqualTo(Multiplicity.ZERO_OR_MORE);
        assertThat(openedBy.inverseName()).isEqualTo("opened");
        assertThat(schema.relationshipType("HeldBy").orElseThrow().outlivesTarget())
                .as("a type that does not declare it does not outlive its targets").isFalse();
    }

    @Test
    @DisplayName("Scenario: A composition that outlives its targets")
    void aCompositionThatOutlivesItsTargets() {
        SchemaDefinition definition = withOutlivesTarget(ReferenceModel.definition(), "HasAddress", true);

        List<Problem> problems = validate(definition);

        assertSingleProblem(problems, ProblemCode.OUTLIVES_TARGET_NOT_ALLOWED, "HasAddress");
        assertThat(problems.getFirst().message()).contains("composition HasAddress");
    }

    @Test
    void aSymmetricTypeCannotOutliveItsTargets() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("Knows", r -> r.from("Person").to("Person").symmetric().outlivesTarget()
                        .inverse("knownBy"))
                .build());

        List<Problem> problems = validate(definition);

        assertSingleProblem(problems, ProblemCode.OUTLIVES_TARGET_NOT_ALLOWED, "Knows");
        assertThat(problems.getFirst().message()).contains("symmetric relationship type Knows");
    }

    @Test
    void aSubtypeDoesNotInheritTheDeclaration() {
        SchemaDefinition definition = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .relationshipType("OpenedBy", r -> r.from("Account").to("Person").targetsPerSource(Multiplicity.ZERO_OR_ONE)
                        .outlivesTarget().inverse("opened"))
                .relationshipType("OpenedByAgent", r -> r.supertype("OpenedBy").from("Account").to("Person")
                        .targetsPerSource(Multiplicity.ZERO_OR_ONE).inverse("openedAsAgent"))
                .build());

        Schema schema = register(definition);

        assertThat(schema.relationshipType("OpenedBy").orElseThrow().outlivesTarget()).isTrue();
        assertThat(schema.relationshipType("OpenedByAgent").orElseThrow().outlivesTarget())
                .as("integrity is checked per concrete type, so a subtype says for itself").isFalse();
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("Scenario: Duplicate type name")
    void duplicateTypeName() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party")
                .trait("Party", t -> { })
                .build();

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_NAME, "Party");
        assertThat(problems.getFirst().message()).contains("entity type Party", "trait Party");
    }

    @Test
    @DisplayName("Scenario: Names that differ only in case")
    void namesThatDifferOnlyInCase() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party")
                .trait("party", t -> { })
                .build();

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.DUPLICATE_NAME, "Party");
        assertThat(problems.getFirst().message()).contains("entity type Party", "trait party");
    }

    @Test
    @DisplayName("Scenario: Inverse name equal to a relationship type name")
    void inverseNameEqualToARelationshipTypeName() {
        SchemaDefinition definition = changeRelationshipType(ReferenceModel.definition(), "Employs", type ->
                withInverseName(type, "owns"));

        assertSingleProblem(validate(definition), ProblemCode.DUPLICATE_NAME, "Employs");
    }

    @Test
    @DisplayName("Scenario: Invalid characters")
    void invalidCharacters() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                changeAttribute(type, "birthDate", attribute -> new AttributeDefinition("birth-date",
                        attribute.valueType(), false, false, Optional.empty(), List.of(), false, Optional.empty(),
                        List.of(), List.of(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty())));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_NAME, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.birth-date");
    }

    // ------------------------------------------------------------------ type hierarchy

    @Test
    @DisplayName("Scenario: Subtypes of an abstract type")
    void subtypesOfAnAbstractType() {
        var party = register(ReferenceModel.definition()).entityType("Party").orElseThrow();

        assertThat(party.subtypes()).containsExactlyInAnyOrder("Person", "Organization");
        assertThat(party.isAbstract()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Declaring element of an inherited attribute")
    void declaringElementOfAnInheritedAttribute() {
        var organization = register(ReferenceModel.definition()).entityType("Organization").orElseThrow();

        assertThat(organization.attributes()).extracting(Attribute::name, Attribute::declaredBy)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("name", "Named"),
                        org.assertj.core.groups.Tuple.tuple("lei", "Organization"));
        assertThat(organization.supertypes()).containsExactly("Party");
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("Scenario: Several problems at once")
    void severalProblemsAtOnce() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party")
                .entityType("Party", e -> e.abstractType())
                .entityType("Account", e -> e.attribute("active", ValueType.BOOLEAN,
                        a -> a.range(Range.atMost(Bound.inclusive(Value.of(1))))))
                .build();

        List<Problem> problems = rejected(registry.register(ORIGIN, definition));
        assertThat(problems).extracting(Problem::code, Problem::path).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(ProblemCode.DUPLICATE_NAME, "Party"),
                org.assertj.core.groups.Tuple.tuple(ProblemCode.RANGE_NOT_ALLOWED, "Account.active"));
        assertThat(problems).allSatisfy(problem -> assertThat(problem.message()).isNotBlank());
        assertThat(registry.current().isEmpty()).isTrue();
        assertThat(registry.history()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Hint for a misspelled reference")
    void hintForAMisspelledReference() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                new EntityTypeDefinition(type.name(), type.isAbstract(), Optional.of("Prty"), type.traits(),
                        type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.UNKNOWN_REFERENCE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization");
        assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Party"));
    }

    @Test
    @DisplayName("Scenario: Expected and found values")
    void expectedAndFoundValues() {
        SchemaDefinition definition = SchemaDefinition.builder().entityType("1Party").build();

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_NAME, "1Party");
        assertThat(problems.getFirst().expected()).hasValueSatisfying(expected -> assertThat(expected).contains("letter"));
        assertThat(problems.getFirst().found()).contains("1Party");
    }

    // ------------------------------------------------------------------ descriptions and examples

    @Test
    @DisplayName("Scenario: Descriptions are reported")
    void descriptionsAreReported() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                        type.attributes(), type.aliases(), type.deprecated(),
                        Optional.of("A company or another legal entity"), type.rules()));

        register(definition);

        assertThat(registry.current().entityType("Organization").orElseThrow().description())
                .contains("A company or another legal entity");
    }

    @Test
    @DisplayName("Scenario: Example that breaks a pattern")
    void exampleThatBreaksAPattern() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                changeAttribute(type, "lei", attribute -> AttributeDefinition.builder("lei", ValueType.STRING)
                        .unique().pattern("[A-Z0-9]{18}[0-9]{2}").examples(Value.of("ABC")).build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_EXAMPLE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization.lei");
    }

    @Test
    @DisplayName("Scenario: Example of the wrong value type")
    void exampleOfTheWrongValueType() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                changeAttribute(type, "birthDate", attribute -> AttributeDefinition.builder("birthDate", ValueType.DATE)
                        .examples(Value.of("tomorrow")).build()));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.INVALID_EXAMPLE, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.birthDate");
    }

    // ------------------------------------------------------------------ rule references

    @Test
    @DisplayName("Scenario: Naming an available rule")
    void namingAnAvailableRule() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Organization", e -> e.rule("lei-checksum", 1))
                .build();

        assertThat(registry.availableRules()).map(RuleDescriptor::name).contains("lei-checksum");
        assertThat(validate(definition)).isEmpty();
        assertThat(register(definition).entityType("Organization").orElseThrow().rules())
                .containsExactly(new RuleReference("lei-checksum", 1));
    }

    @Test
    @DisplayName("Scenario: Unknown rule version")
    void unknownRuleVersion() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Organization", type ->
                withRules(type, new RuleReference("lei-checksum", 2)));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.UNKNOWN_RULE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization.lei-checksum");
        assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("1"));
    }

    @Test
    @DisplayName("Scenario: Rule for another type")
    void ruleForAnotherType() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                withRules(type, new RuleReference("lei-checksum", 1)));

        List<Problem> problems = validate(definition);
        assertSingleProblem(problems, ProblemCode.RULE_TYPE_MISMATCH, "Person");
        assertThat(problems.getFirst().path()).isEqualTo("Person.lei-checksum");
    }

    @Test
    @DisplayName("Scenario: Rule inherited from a supertype")
    void ruleInheritedFromASupertype() {
        SchemaRegistry withNotBlank = registryWith(NAME_NOT_BLANK);
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Party", type ->
                withRules(type, new RuleReference("name-not-blank", 1)));

        Schema schema = applied(withNotBlank.register(ORIGIN, definition)).schema();

        assertThat(schema.entityType("Organization").orElseThrow().effectiveRules())
                .contains(new EffectiveRule(new RuleReference("name-not-blank", 1), "Party"));
        assertThat(schema.entityType("Organization").orElseThrow().rules())
                .doesNotContain(new RuleReference("name-not-blank", 1));
    }

    @Test
    @DisplayName("Scenario: Rule named twice in a hierarchy")
    void ruleNamedTwiceInAHierarchy() {
        SchemaDefinition definition = changeEntityType(
                changeEntityType(ReferenceModel.definition(), "Party", type ->
                        withRules(type, new RuleReference("name-not-blank", 1))),
                "Organization", type ->
                        withRules(type, new RuleReference("lei-checksum", 1), new RuleReference("name-not-blank", 1)));

        List<Problem> problems = registryWith(NAME_NOT_BLANK).validate(ORIGIN, definition);

        assertSingleProblem(problems, ProblemCode.DUPLICATE_RULE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization.name-not-blank");
    }

    @Test
    @DisplayName("Scenario: Two versions of a rule in a hierarchy")
    void twoVersionsOfARuleInAHierarchy() {
        RuleDescriptor versionTwo = new RuleDescriptor("name-not-blank", 2, "The name has a visible character.",
                "Party", List.of());
        SchemaDefinition definition = changeEntityType(
                changeEntityType(ReferenceModel.definition(), "Party", type ->
                        withRules(type, new RuleReference("name-not-blank", 1))),
                "Organization", type ->
                        withRules(type, new RuleReference("lei-checksum", 1), new RuleReference("name-not-blank", 2)));

        List<Problem> problems = registryWith(NAME_NOT_BLANK, versionTwo).validate(ORIGIN, definition);

        assertSingleProblem(problems, ProblemCode.DUPLICATE_RULE, "Organization");
        assertThat(problems.getFirst().path()).isEqualTo("Organization.name-not-blank");
    }

    // ------------------------------------------------------------------ available rules

    @Test
    @DisplayName("Scenario: Listing available rules")
    void listingAvailableRules() {
        List<RuleDescriptor> listed = registry.availableRules();

        assertThat(listed).extracting(RuleDescriptor::name, RuleDescriptor::version, RuleDescriptor::appliesTo)
                .containsExactly(
                        tuple("lei-checksum", 1, "Organization"),
                        tuple("total-ownership-at-most-100", 1, "Owns"));
        assertThat(listed).allSatisfy(rule -> assertThat(rule.description()).isNotBlank());
        assertThat(listed.get(0).codes()).map(RuleCode::code).containsExactly("invalid-check-digits");
        assertThat(listed.get(1).codes()).map(RuleCode::code).containsExactly("over-100-percent");
        assertThat(listed).flatExtracting(RuleDescriptor::codes)
                .allSatisfy(code -> assertThat(code.explanation()).isNotBlank());
    }

    @Test
    @DisplayName("Scenario: Same rule given twice")
    void sameRuleGivenTwice() {
        RuleDescriptor lei = ReferenceModel.rules().getFirst();

        assertThatThrownBy(() -> SchemaRegistries.inMemory(new TestClock(), List.of(lei, lei)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Scenario: Rule with an invalid name")
    void ruleWithAnInvalidName() {
        RuleDescriptor invalid = new RuleDescriptor("Lei_Checksum", 1, "The LEI's check digits are valid.",
                "Organization", List.of());

        assertThatThrownBy(() -> SchemaRegistries.inMemory(new TestClock(), List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ problem codes

    @Test
    @DisplayName("Scenario: Explaining a code")
    void explainingACode() {
        assertThat(ProblemCode.UNKNOWN_REFERENCE.explanation()).isNotBlank();
    }

    // ------------------------------------------------------------------ deprecation

    @Test
    @DisplayName("Scenario: Deprecated attribute is reported")
    void deprecatedAttributeIsReported() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                changeAttribute(type, "birthDate", attribute -> new AttributeDefinition(attribute.name(),
                        attribute.valueType(), attribute.required(), attribute.unique(), attribute.range(),
                        attribute.aliases(), true, attribute.description(), attribute.examples(),
                        attribute.allowedValues(), attribute.pattern(), attribute.minLength(), attribute.maxLength())));

        Attribute birthDate = register(definition).entityType("Person").orElseThrow().attribute("birthDate").orElseThrow();
        assertThat(birthDate.deprecated()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static EntityTypeDefinition withAttribute(EntityTypeDefinition type, AttributeDefinition attribute) {
        List<AttributeDefinition> attributes = new java.util.ArrayList<>(type.attributes());
        attributes.add(attribute);
        return io.github.enricoag1982.cairn.fixtures.Definitions.withAttributes(type, attributes);
    }

    private static EntityTypeDefinition withRules(EntityTypeDefinition type, RuleReference... rules) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), List.of(rules));
    }

    private static RelationshipTypeDefinition withSourcesPerTarget(RelationshipTypeDefinition type, Multiplicity value) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(), value,
                type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules());
    }

    private static RelationshipTypeDefinition withInverseName(RelationshipTypeDefinition type, String inverseName) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), inverseName, type.inverseAliases(), type.kind(), type.symmetric(),
                type.supertype(), type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules());
    }
}
