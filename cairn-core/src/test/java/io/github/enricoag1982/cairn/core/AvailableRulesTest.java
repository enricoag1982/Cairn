package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.EffectiveRule;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rules that are available to a registry, and how the resolved schema reports descriptions and rules. */
class AvailableRulesTest {

    private static final RuleDescriptor LEI = new RuleDescriptor("lei-checksum", 1,
            "The LEI's check digits are valid.", "Organization",
            List.of(new RuleCode("invalid-check-digits", "The check digits do not match the LEI.")));
    private static final RuleDescriptor OWNERSHIP = new RuleDescriptor("total-ownership-at-most-100", 1,
            "The owners of a party hold at most 100% of it together.", "Owns",
            List.of(new RuleCode("over-100-percent", "The shares add up to more than 100%.")));
    private static final RuleDescriptor NOT_BLANK = new RuleDescriptor("name-not-blank", 1,
            "The name has a visible character.", "Party", List.of());

    private static EntityTypeDefinition naming(EntityTypeDefinition type, RuleReference... rules) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                type.attributes(), type.aliases(), type.deprecated(), type.description(), List.of(rules));
    }

    private static EntityTypeDefinition describedAs(EntityTypeDefinition type, String description) {
        return new EntityTypeDefinition(type.name(), type.isAbstract(), type.supertype(), type.traits(),
                type.attributes(), type.aliases(), type.deprecated(), java.util.Optional.of(description), type.rules());
    }

    private static SchemaRegistry registry(RuleDescriptor... rules) {
        return SchemaRegistries.inMemory(new TestClock(), List.of(rules));
    }

    @Test
    void aRegistryWithoutRulesListsNone() {
        assertThat(SchemaRegistries.inMemory().availableRules()).isEmpty();
        assertThat(SchemaRegistries.inMemory(new TestClock()).availableRules()).isEmpty();
    }

    @Test
    void availableRulesAreListedSortedByNameAndVersionWithEverythingTheyDescribe() {
        RuleDescriptor leiTwo = new RuleDescriptor("lei-checksum", 2, "Version 2.", "Organization", List.of());

        List<RuleDescriptor> listed = registry(OWNERSHIP, leiTwo, LEI).availableRules();

        assertThat(listed).containsExactly(LEI, leiTwo, OWNERSHIP);
        assertThat(listed.getFirst().codes()).containsExactly(
                new RuleCode("invalid-check-digits", "The check digits do not match the LEI."));
        assertThatThrownBy(() -> listed.add(NOT_BLANK)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theListIsCopiedWhenTheRegistryIsCreated() {
        List<RuleDescriptor> given = new ArrayList<>(List.of(LEI));
        SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), given);
        given.add(OWNERSHIP);

        assertThat(registry.availableRules()).containsExactly(LEI);
    }

    @Test
    void twoRulesWithTheSameNameAndVersionAreRejected() {
        assertThatThrownBy(() -> registry(LEI, LEI)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lei-checksum");
        assertThatThrownBy(() -> registry(LEI, OWNERSHIP,
                new RuleDescriptor("lei-checksum", 1, "Another.", "Person", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoVersionsOfARuleAreFine() {
        assertThat(registry(LEI, new RuleDescriptor("lei-checksum", 2, "Next.", "Organization", List.of()))
                .availableRules()).hasSize(2);
    }

    @Test
    void aRegistryAcceptsAReferenceToAnAvailableRuleAndRejectsOthers() {
        SchemaDefinition model = ReferenceModel.definition();

        applied(registry(LEI, OWNERSHIP).register(ReferenceModel.origin("1.0.0"), model));

        List<Problem> problems = rejected(registry(OWNERSHIP).register(ReferenceModel.origin("1.0.0"), model));
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_RULE);
            assertThat(problem.path()).isEqualTo("Organization.lei-checksum");
        });
        assertThat(registry(OWNERSHIP).validate(ReferenceModel.origin("1.0.0"), model)).isEqualTo(problems);
        assertThat(registry(LEI, OWNERSHIP).validate(ReferenceModel.origin("1.0.0"), model)).isEmpty();
        assertThat(rejected(registry().register(ReferenceModel.origin("1.0.0"), model))).map(Problem::path)
                .containsExactlyInAnyOrder("Organization.lei-checksum", "Owns.total-ownership-at-most-100");
    }

    @Test
    void aRuleOfARelationshipTypeIsReportedWithItsInheritance() {
        Schema schema = applied(registry(LEI, OWNERSHIP).register(ReferenceModel.origin("1.0.0"),
                ReferenceModel.definition())).schema();

        RelationshipType owns = schema.relationshipType("Owns").orElseThrow();
        assertThat(owns.rules()).containsExactly(new RuleReference("total-ownership-at-most-100", 1));
        assertThat(owns.effectiveRules()).containsExactly(
                new EffectiveRule(new RuleReference("total-ownership-at-most-100", 1), "Owns"));
    }

    @Test
    void aRuleOfASupertypeIsInheritedByItsSubtypesAndOwnRulesComeFirst() {
        RuleDescriptor leiForParty = new RuleDescriptor("party-check", 1, "Checks parties.", "Party", List.of());
        SchemaDefinition definition = changeEntityType(
                changeEntityType(ReferenceModel.definition(), "Party",
                        type -> naming(type, new RuleReference("name-not-blank", 1), new RuleReference("party-check", 1))),
                "Organization", type -> naming(type, new RuleReference("lei-checksum", 1)));

        Schema schema = applied(registry(NOT_BLANK, leiForParty, LEI, OWNERSHIP)
                .register(ReferenceModel.origin("1.0.0"), definition)).schema();

        EntityType organization = schema.entityType("Organization").orElseThrow();
        assertThat(organization.rules()).containsExactly(new RuleReference("lei-checksum", 1));
        assertThat(organization.effectiveRules()).containsExactly(
                new EffectiveRule(new RuleReference("lei-checksum", 1), "Organization"),
                new EffectiveRule(new RuleReference("name-not-blank", 1), "Party"),
                new EffectiveRule(new RuleReference("party-check", 1), "Party"));
        assertThat(schema.entityType("Person").orElseThrow().rules()).isEmpty();
        assertThat(schema.entityType("Person").orElseThrow().effectiveRules()).containsExactly(
                new EffectiveRule(new RuleReference("name-not-blank", 1), "Party"),
                new EffectiveRule(new RuleReference("party-check", 1), "Party"));
        assertThat(schema.entityType("Party").orElseThrow().effectiveRules()).hasSize(2);
        assertThat(schema.entityType("Address").orElseThrow().effectiveRules()).isEmpty();
    }

    @Test
    void inheritedRulesFollowAMultiLevelHierarchyNearestFirst() {
        RuleDescriptor legal = new RuleDescriptor("legal-check", 1, "Checks legal parties.", "Legal", List.of());
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().rule("name-not-blank", 1))
                .entityType("Legal", e -> e.abstractType().supertype("Party").rule("legal-check", 1))
                .entityType("Organization", e -> e.supertype("Legal").rule("lei-checksum", 1))
                .build();

        Schema schema = applied(registry(NOT_BLANK, legal, LEI).register(ReferenceModel.origin("1.0.0"), definition))
                .schema();

        assertThat(schema.entityType("Organization").orElseThrow().effectiveRules()).extracting(EffectiveRule::declaredBy)
                .containsExactly("Organization", "Legal", "Party");
    }

    @Test
    void aSubtypeNamedByAnAliasReportsTheDeclaringTypeByName() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().aliases("Actor").rule("name-not-blank", 1))
                .entityType("Organization", e -> e.supertype("Actor"))
                .build();

        Schema schema = applied(registry(NOT_BLANK).register(ReferenceModel.origin("1.0.0"), definition)).schema();

        assertThat(schema.entityType("Organization").orElseThrow().effectiveRules())
                .containsExactly(new EffectiveRule(new RuleReference("name-not-blank", 1), "Party"));
    }

    @Test
    void descriptionsExamplesAndStringConstraintsAreReported() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .trait("Named", t -> t.description("Something with a name")
                        .attribute("name", ValueType.STRING, a -> a.required()))
                .entityType("Party", e -> e.abstractType().includes("Named").description("Anyone we deal with"))
                .entityType("Organization", e -> e.supertype("Party")
                        .description("A company or another legal entity")
                        .attribute("lei", ValueType.STRING, a -> a.description("Legal Entity Identifier")
                                .pattern("[A-Z0-9]{18}[0-9]{2}").minLength(20).maxLength(20)
                                .examples(Literal.of("5493001KJTIIGC8Y1R12")))
                        .attribute("segment", ValueType.STRING, a -> a.allowedValues("Corporate", "Retail")))
                .relationshipType("Owns", r -> r.from("Organization").to("Party").inverse("ownedBy")
                        .description("Ownership of one party by an organization")
                        .attribute("share", ValueType.DECIMAL, a -> a.description("The share in percent")))
                .build();

        Schema schema = applied(SchemaRegistries.inMemory(new TestClock()).register(ReferenceModel.origin("1.0.0"), definition))
                .schema();

        assertThat(schema.trait("Named").orElseThrow().description()).contains("Something with a name");
        assertThat(schema.entityType("Party").orElseThrow().description()).contains("Anyone we deal with");
        EntityType organization = schema.entityType("Organization").orElseThrow();
        assertThat(organization.description()).contains("A company or another legal entity");
        var lei = organization.attribute("lei").orElseThrow();
        assertThat(lei.description()).contains("Legal Entity Identifier");
        assertThat(lei.pattern()).contains("[A-Z0-9]{18}[0-9]{2}");
        assertThat(lei.minLength()).hasValue(20);
        assertThat(lei.maxLength()).hasValue(20);
        assertThat(lei.examples()).containsExactly(Literal.of("5493001KJTIIGC8Y1R12"));
        assertThat(organization.attribute("segment").orElseThrow().allowedValues()).containsExactly("Corporate", "Retail");
        assertThat(organization.attribute("name").orElseThrow().description()).isEmpty();
        assertThat(schema.relationshipType("Owns").orElseThrow().description())
                .contains("Ownership of one party by an organization");
        assertThat(schema.relationshipType("Owns").orElseThrow().attribute("share").orElseThrow().description())
                .contains("The share in percent");
    }

    @Test
    void changingOnlyADescriptionIsAppliedAndReported() {
        SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules());
        applied(registry.register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()));

        Schema after = applied(registry.register(ReferenceModel.origin("1.1.0"), changeEntityType(
                ReferenceModel.definition(), "Organization",
                type -> describedAs(type, "A company or another legal entity")))).schema();

        assertThat(after.entityType("Organization").orElseThrow().description())
                .contains("A company or another legal entity");
    }
}
