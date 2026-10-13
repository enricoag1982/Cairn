package io.github.enricoag1982.sysrec.core;

import static io.github.enricoag1982.sysrec.core.Results.applied;
import static io.github.enricoag1982.sysrec.core.Results.locatedAt;
import static io.github.enricoag1982.sysrec.core.Results.rejected;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeAttribute;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.changeRelationshipType;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.plus;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.withAttributes;
import static io.github.enricoag1982.sysrec.fixtures.Definitions.withoutEntityType;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.sysrec.fixtures.ReferenceModel;
import io.github.enricoag1982.sysrec.schema.AttributeDefinition;
import io.github.enricoag1982.sysrec.schema.Bound;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Literal;
import io.github.enricoag1982.sysrec.schema.Multiplicity;
import io.github.enricoag1982.sysrec.schema.Problem;
import io.github.enricoag1982.sysrec.schema.ProblemCode;
import io.github.enricoag1982.sysrec.schema.Range;
import io.github.enricoag1982.sysrec.schema.RelationshipKind;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Schema;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaRegistry;
import io.github.enricoag1982.sysrec.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One test for each compatible and each breaking change listed in the {@code schema-evolution} spec.
 * Every test registers {@link #BASE} as version 1.0.0 and a changed definition as version 1.1.0.
 */
class CompatibilityRulesTest {

    /** The reference model plus a few elements that some rules need. */
    static final SchemaDefinition BASE = plus(
            changeEntityType(ReferenceModel.definition(), "Organization", type -> withExtra(type,
                    AttributeDefinition.builder("headcount", ValueType.INT).build(),
                    AttributeDefinition.builder("revenue", ValueType.LONG).build())),
            SchemaDefinition.builder()
                    .entityType("Branch", e -> e.attribute("code", ValueType.STRING))
                    .relationshipType("Knows", r -> r.from("Person").to("Person").symmetric().inverse("knownBy"))
                    .build());

    private SchemaRegistry registry;
    private Schema before;

    @BeforeEach
    void registerBase() {
        registry = SchemaRegistries.inMemory(new TestClock());
        before = applied(registry.register(ReferenceModel.origin("1.0.0"), BASE)).schema();
    }

    private Schema accepted(SchemaDefinition changed) {
        return applied(registry.register(ReferenceModel.origin("1.1.0"), changed)).schema();
    }

    private void breaking(SchemaDefinition changed, String locatedAt) {
        List<Problem> problems = rejected(registry.register(ReferenceModel.origin("1.1.0"), changed));
        assertThat(problems).allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE));
        assertThat(problems).as("a problem located at " + locatedAt + " in " + problems)
                .anyMatch(problem -> locatedAt(problem, locatedAt));
        assertThat(registry.current()).isSameAs(before);
    }

    @Nested
    class CompatibleChanges {

        @Test
        void addingAnEntityTypeOrTrait() {
            accepted(plus(BASE, SchemaDefinition.builder()
                    .entityType("Contract", e -> e.attribute("signed", ValueType.DATE, a -> a.required()))
                    .trait("Audited", t -> t.attribute("createdBy", ValueType.STRING, a -> a.required()))
                    .build()));
        }

        @Test
        void addingARelationshipTypeThatRequiresNothingOfExistingEntities() {
            accepted(plus(BASE, SchemaDefinition.builder()
                    .entityType("Contract")
                    .relationshipType("Manages", r -> r.from("Person").to("Organization").inverse("managedBy"))
                    .relationshipType("SignedBy", r -> r.from("Contract").to("Party")
                            .targetsPerSource(Multiplicity.EXACTLY_ONE).inverse("signed"))
                    .build()));
        }

        @Test
        void addingAnOptionalAttribute() {
            accepted(entity(BASE, "Account", type -> withExtra(type, AttributeDefinition.builder("iban", ValueType.STRING).build())));
        }

        @Test
        void includingATraitWhoseAttributesAreAllOptional() {
            SchemaDefinition withTrait = plus(BASE, SchemaDefinition.builder()
                    .trait("Audited", t -> t.attribute("createdBy", ValueType.STRING).attribute("createdAt", ValueType.INSTANT))
                    .build());

            Schema after = accepted(entity(withTrait, "Account", type -> new EntityTypeDefinition(type.name(),
                    type.isAbstract(), type.supertype(), List.of("Audited"), type.attributes(), type.aliases(),
                    type.deprecated())));

            assertThat(after.entityType("Account").orElseThrow().attribute("createdBy")).isPresent();
        }

        @Test
        void renamingATypeWithAnAlias() {
            Schema after = accepted(entity(BASE, "Branch", type -> new EntityTypeDefinition("Office", type.isAbstract(),
                    type.supertype(), type.traits(), type.attributes(), List.of("Branch"), type.deprecated())));

            assertThat(after.entityType("Branch").orElseThrow().id()).isEqualTo(before.entityType("Branch").orElseThrow().id());
        }

        @Test
        void renamingAnInverseNameWithAnAlias() {
            Schema after = accepted(relationship(BASE, "Owns", type -> new RelationshipTypeDefinition(type.name(),
                    type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(), "owners",
                    List.of("ownedBy"), type.kind(), type.symmetric(), type.supertype(), type.attributes(),
                    type.aliases(), type.deprecated())));

            assertThat(after.relationshipTypeByInverseName("ownedBy").orElseThrow().name()).isEqualTo("Owns");
        }

        @Test
        void wideningIntOrLongToDecimal() {
            SchemaDefinition widened = entity(BASE, "Organization", type -> attribute(
                    attribute(type, "headcount", a -> withValueType(a, ValueType.DECIMAL)),
                    "revenue", a -> withValueType(a, ValueType.DECIMAL)));

            accepted(widened);
        }

        @Test
        void makingARequiredAttributeOptional() {
            accepted(entity(BASE, "Account", type -> attribute(type, "currency", a -> withFlags(a, false, false))));
        }

        @Test
        void removingAUniqueConstraint() {
            accepted(entity(BASE, "Account", type -> attribute(type, "number", a -> withFlags(a, true, false))));
        }

        @Test
        void wideningARange() {
            SchemaDefinition widened = relationship(BASE, "Owns", type -> relationshipAttribute(type, "share", a ->
                    withRange(a, Optional.of(Range.between(Bound.inclusive(Literal.of(0)), Bound.inclusive(Literal.of(100)))))));
            accepted(widened);
        }

        @Test
        void removingARange() {
            accepted(relationship(BASE, "Owns", type -> relationshipAttribute(type, "share", a -> withRange(a, Optional.empty()))));
        }

        @Test
        void movingAnOptionalAttributeWithoutUniqueConstraintToASupertype() {
            SchemaDefinition moved = entity(entity(BASE, "Person", type -> withAttributes(type, List.of())),
                    "Party", type -> withExtra(type, AttributeDefinition.builder("birthDate", ValueType.DATE).build()));

            Schema after = accepted(moved);

            assertThat(after.entityType("Party").orElseThrow().attribute("birthDate").orElseThrow().id())
                    .isEqualTo(before.entityType("Person").orElseThrow().attribute("birthDate").orElseThrow().id());
        }

        @Test
        void markingATypeOrAttributeAsDeprecated() {
            SchemaDefinition deprecated = entity(entity(BASE, "Branch", type -> new EntityTypeDefinition(type.name(),
                    type.isAbstract(), type.supertype(), type.traits(), type.attributes(), type.aliases(), true)),
                    "Person", type -> attribute(type, "birthDate", a -> new AttributeDefinition(a.name(), a.valueType(),
                            a.required(), a.unique(), a.range(), a.aliases(), true)));

            accepted(deprecated);
        }

        @Test
        void makingAnAbstractEntityTypeConcrete() {
            accepted(entity(BASE, "Party", type -> withAbstract(type, false)));
        }

        @Test
        void changingARelationshipEndToOneOfItsSupertypes() {
            accepted(relationship(BASE, "Employs", type -> withEnds(type, type.source(), "Party")));
        }

        @Test
        void relaxingAMultiplicityByLoweringAMinimum() {
            accepted(relationship(BASE, "HeldBy", type -> withMultiplicities(type, Multiplicity.ZERO_OR_ONE,
                    type.sourcesPerTarget())));
        }
    }

    @Nested
    class BreakingChanges {

        @Test
        void removingAType() {
            breaking(withoutEntityType(BASE, "Branch"), "Branch");
        }

        @Test
        void removingATraitFromATypesIncludedTraits() {
            breaking(entity(BASE, "Party", type -> new EntityTypeDefinition(type.name(), type.isAbstract(),
                    type.supertype(), List.of(), type.attributes(), type.aliases(), type.deprecated())), "Party");
        }

        @Test
        void renamingWithoutAnAlias() {
            breaking(entity(BASE, "Branch", type -> new EntityTypeDefinition("Office", type.isAbstract(),
                    type.supertype(), type.traits(), type.attributes(), List.of(), type.deprecated())), "Branch");
        }

        @Test
        void narrowingAValueType() {
            breaking(entity(BASE, "Organization", type -> attribute(type, "revenue", a -> withValueType(a, ValueType.INT))),
                    "Organization.revenue");
        }

        @Test
        void narrowingARange() {
            breaking(relationship(BASE, "Owns", type -> relationshipAttribute(type, "share", a -> withRange(a,
                    Optional.of(Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(50))))))),
                    "Owns.share");
        }

        @Test
        void narrowingARelationshipEnd() {
            breaking(relationship(BASE, "Owns", type -> withEnds(type, type.source(), "Organization")), "Owns");
        }

        @Test
        void narrowingAMultiplicity() {
            breaking(relationship(BASE, "Owns", type -> withMultiplicities(type, Multiplicity.ZERO_OR_ONE,
                    type.sourcesPerTarget())), "Owns");
        }

        @Test
        void addingARequiredAttributeToAnExistingType() {
            breaking(entity(BASE, "Account", type -> withExtra(type,
                    AttributeDefinition.builder("iban", ValueType.STRING).required().build())), "Account.iban");
        }

        @Test
        void makingAnOptionalAttributeRequired() {
            breaking(entity(BASE, "Organization", type -> attribute(type, "lei", a -> withFlags(a, true, true))),
                    "Organization.lei");
        }

        @Test
        void addingARelationshipTypeThatRequiresARelationshipForExistingEntities() {
            breaking(plus(BASE, SchemaDefinition.builder()
                    .relationshipType("BelongsTo", r -> r.from("Account").to("Organization")
                            .targetsPerSource(Multiplicity.EXACTLY_ONE).inverse("accounts"))
                    .build()), "BelongsTo");
        }

        @Test
        void addingAUniqueConstraintToAnExistingAttribute() {
            breaking(entity(BASE, "Account", type -> attribute(type, "currency", a -> withFlags(a, true, true))),
                    "Account.currency");
        }

        @Test
        void movingARequiredOrUniqueAttributeToASupertype() {
            SchemaDefinition moved = entity(entity(BASE, "Organization", type -> withAttributes(type,
                    type.attributes().stream().filter(a -> !a.name().equals("lei")).toList())),
                    "Party", type -> withExtra(type, AttributeDefinition.builder("lei", ValueType.STRING).unique().build()));

            breaking(moved, "Party.lei");
        }

        @Test
        void addingASupertype() {
            breaking(plus(entity(BASE, "Account", type -> withSupertype(type, Optional.of("Asset"))),
                    SchemaDefinition.builder().entityType("Asset", e -> e.abstractType()).build()), "Account");
        }

        @Test
        void removingASupertype() {
            breaking(entity(BASE, "Organization", type -> withSupertype(type, Optional.empty())), "Organization");
        }

        @Test
        void changingASupertype() {
            breaking(plus(entity(BASE, "Organization", type -> withSupertype(type, Optional.of("LegalSubject"))),
                    SchemaDefinition.builder().entityType("LegalSubject", e -> e.abstractType().includes("Named")).build()),
                    "Organization");
        }

        @Test
        void makingAConcreteEntityTypeAbstract() {
            breaking(entity(BASE, "Account", type -> withAbstract(type, true)), "Account");
        }

        @Test
        void changingACompositionToAnAssociation() {
            breaking(relationship(BASE, "HasAddress", type -> withKind(type, RelationshipKind.ASSOCIATION, false)),
                    "HasAddress");
        }

        @Test
        void changingWhetherARelationshipTypeIsSymmetric() {
            breaking(relationship(BASE, "Knows", type -> withKind(type, RelationshipKind.ASSOCIATION, false)), "Knows");
        }

        @Test
        void removingAnAliasIsAnotherPropertyChange() {
            SchemaRegistry withAlias = SchemaRegistries.inMemory(new TestClock());
            SchemaDefinition aliased = entity(BASE, "Branch", type -> new EntityTypeDefinition(type.name(),
                    type.isAbstract(), type.supertype(), type.traits(), type.attributes(), List.of("Office"), false));
            applied(withAlias.register(ReferenceModel.origin("1.0.0"), aliased));

            List<Problem> problems = rejected(withAlias.register(ReferenceModel.origin("1.1.0"), BASE));

            assertThat(problems).anyMatch(problem -> locatedAt(problem, "Branch") && problem.message().contains("alias"));
        }

        @Test
        void undeprecatingIsAnotherPropertyChange() {
            SchemaRegistry withDeprecation = SchemaRegistries.inMemory(new TestClock());
            SchemaDefinition deprecated = entity(BASE, "Branch", type -> new EntityTypeDefinition(type.name(),
                    type.isAbstract(), type.supertype(), type.traits(), type.attributes(), type.aliases(), true));
            applied(withDeprecation.register(ReferenceModel.origin("1.0.0"), deprecated));

            List<Problem> problems = rejected(withDeprecation.register(ReferenceModel.origin("1.1.0"), BASE));

            assertThat(problems).singleElement().satisfies(problem -> assertThat(locatedAt(problem, "Branch")).isTrue());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SchemaDefinition entity(SchemaDefinition definition, String name, UnaryOperator<EntityTypeDefinition> change) {
        return changeEntityType(definition, name, change);
    }

    private static SchemaDefinition relationship(SchemaDefinition definition, String name,
            UnaryOperator<RelationshipTypeDefinition> change) {
        return changeRelationshipType(definition, name, change);
    }

    private static EntityTypeDefinition withExtra(EntityTypeDefinition type, AttributeDefinition... extra) {
        List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
        attributes.addAll(List.of(extra));
        return withAttributes(type, attributes);
    }

    private static EntityTypeDefinition attribute(EntityTypeDefinition type, String name, UnaryOperator<AttributeDefinition> change) {
        return changeAttribute(type, name, change);
    }

    private static EntityTypeDefinition withAbstract(EntityTypeDefinition type, boolean isAbstract) {
        return new EntityTypeDefinition(type.name(), isAbstract, type.supertype(), type.traits(), type.attributes(),
                type.aliases(), type.deprecated());
    }

    private static EntityTypeDefinition withSupertype(EntityTypeDefinition type, Optional<String> supertype) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), supertype, type.traits(), type.attributes(),
                type.aliases(), type.deprecated());
    }

    private static AttributeDefinition withValueType(AttributeDefinition attribute, ValueType valueType) {
        return new AttributeDefinition(attribute.name(), valueType, attribute.required(), attribute.unique(),
                attribute.range(), attribute.aliases(), attribute.deprecated());
    }

    private static AttributeDefinition withFlags(AttributeDefinition attribute, boolean required, boolean unique) {
        return new AttributeDefinition(attribute.name(), attribute.valueType(), required, unique, attribute.range(),
                attribute.aliases(), attribute.deprecated());
    }

    private static AttributeDefinition withRange(AttributeDefinition attribute, Optional<Range> range) {
        return new AttributeDefinition(attribute.name(), attribute.valueType(), attribute.required(), attribute.unique(),
                range, attribute.aliases(), attribute.deprecated());
    }

    private static RelationshipTypeDefinition relationshipAttribute(RelationshipTypeDefinition type, String name,
            UnaryOperator<AttributeDefinition> change) {
        List<AttributeDefinition> attributes = type.attributes().stream()
                .map(attribute -> attribute.name().equals(name) ? change.apply(attribute) : attribute).toList();
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(),
                type.supertype(), attributes, type.aliases(), type.deprecated());
    }

    private static RelationshipTypeDefinition withEnds(RelationshipTypeDefinition type, String source, String target) {
        return new RelationshipTypeDefinition(type.name(), source, target, type.targetsPerSource(), type.sourcesPerTarget(),
                type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                type.attributes(), type.aliases(), type.deprecated());
    }

    private static RelationshipTypeDefinition withMultiplicities(RelationshipTypeDefinition type, Multiplicity targetsPerSource,
            Multiplicity sourcesPerTarget) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), targetsPerSource, sourcesPerTarget,
                type.inverseName(), type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                type.attributes(), type.aliases(), type.deprecated());
    }

    private static RelationshipTypeDefinition withKind(RelationshipTypeDefinition type, RelationshipKind kind, boolean symmetric) {
        return new RelationshipTypeDefinition(type.name(), type.source(), type.target(), type.targetsPerSource(),
                type.sourcesPerTarget(), type.inverseName(), type.inverseAliases(), kind, symmetric, type.supertype(),
                type.attributes(), type.aliases(), type.deprecated());
    }
}
