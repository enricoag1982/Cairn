package io.github.enricoag1982.sysrec.core;

import static io.github.enricoag1982.sysrec.core.Results.applied;
import static io.github.enricoag1982.sysrec.core.Results.assertSingleProblem;
import static io.github.enricoag1982.sysrec.core.Results.rejected;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeRelationshipType;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.plus;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.sysrec.fixtures.ReferenceModel;
import io.github.enricoag1982.sysrec.schema.Attribute;
import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.Bound;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Literal;
import io.github.enricoag1982.sysrec.schema.Multiplicity;
import io.github.enricoag1982.sysrec.schema.Origin;
import io.github.enricoag1982.sysrec.schema.Problem;
import io.github.enricoag1982.sysrec.schema.ProblemCode;
import io.github.enricoag1982.sysrec.schema.Range;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Schema;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaRegistry;
import io.github.enricoag1982.sysrec.schema.ValueType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code type-model} spec, one test each. */
class TypeModelScenariosTest {

    private static final Origin ORIGIN = ReferenceModel.origin("1.0.0");

    private final SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock());

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
                        .range(Range.atLeast(Bound.inclusive(Literal.of(0)))).build()));

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
        assertThat(share.range()).contains(
                Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100))));
    }

    @Test
    void emptyAndMismatchedRangesAreReported() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e
                        .attribute("limit", ValueType.INT, a -> a.range(
                                Range.between(Bound.inclusive(Literal.of(10)), Bound.exclusive(Literal.of(10)))))
                        .attribute("opened", ValueType.DATE, a -> a.range(Range.atLeast(Bound.inclusive(Literal.of(1))))))
                .build();

        assertThat(validate(definition)).map(Problem::code)
                .containsExactlyInAnyOrder(ProblemCode.RANGE_EMPTY, ProblemCode.RANGE_LITERAL_MISMATCH);
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
                        attribute.valueType(), false, false, Optional.empty(), List.of(), false)));

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
                        a -> a.range(Range.atMost(Bound.inclusive(Literal.of(1))))))
                .build();

        List<Problem> problems = rejected(registry.register(ORIGIN, definition));
        assertThat(problems).extracting(Problem::code, Problem::path).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(ProblemCode.DUPLICATE_NAME, "Party"),
                org.assertj.core.groups.Tuple.tuple(ProblemCode.RANGE_NOT_ALLOWED, "Account.active"));
        assertThat(problems).allSatisfy(problem -> assertThat(problem.message()).isNotBlank());
        assertThat(registry.current().isEmpty()).isTrue();
        assertThat(registry.history()).isEmpty();
    }

    // ------------------------------------------------------------------ deprecation

    @Test
    @DisplayName("Scenario: Deprecated attribute is reported")
    void deprecatedAttributeIsReported() {
        SchemaDefinition definition = changeEntityType(ReferenceModel.definition(), "Person", type ->
                changeAttribute(type, "birthDate", attribute -> new AttributeDefinition(attribute.name(),
                        attribute.valueType(), attribute.required(), attribute.unique(), attribute.range(),
                        attribute.aliases(), true)));

        Attribute birthDate = register(definition).entityType("Person").orElseThrow().attribute("birthDate").orElseThrow();
        assertThat(birthDate.deprecated()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static EntityTypeDefinition withAttribute(EntityTypeDefinition type, AttributeDefinition attribute) {
        List<AttributeDefinition> attributes = new java.util.ArrayList<>(type.attributes());
        attributes.add(attribute);
        return io.github.enricoag1982.sysrec.fixtures.Definitions.withAttributes(type, attributes);
    }

    private static RelationshipTypeDefinition withSourcesPerTarget(RelationshipTypeDefinition type, Multiplicity value) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(), value,
                type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                type.attributes(), type.aliases(), type.deprecated());
    }

    private static RelationshipTypeDefinition withInverseName(RelationshipTypeDefinition type, String inverseName) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), inverseName, type.inverseAliases(), type.kind(), type.symmetric(),
                type.supertype(), type.attributes(), type.aliases(), type.deprecated());
    }
}
