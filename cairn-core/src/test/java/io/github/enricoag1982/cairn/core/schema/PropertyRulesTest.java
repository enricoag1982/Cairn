package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeBuilder;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.EntityTypeBuilder;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PropertyRulesTest {

    private record Element(String name, String colour) {
    }

    @Test
    void aPropertyWithoutARuleIsBreaking() {
        PropertyRules.Context context = new PropertyRules.Context(CompiledSchema.EMPTY, CompiledSchema.EMPTY, "Party",
                "colour", "red", "blue", new Element("Party", "red"), new Element("Party", "blue"));

        assertThat(PropertyRules.classify("entity", context)).singleElement().satisfies(change -> {
            assertThat(change.compatible()).isFalse();
            assertThat(change.path()).isEqualTo("Party");
            assertThat(change.description()).contains("colour", "red", "blue");
        });
    }

    @Test
    void anUnknownElementKindIsBreakingToo() {
        PropertyRules.Context context = new PropertyRules.Context(CompiledSchema.EMPTY, CompiledSchema.EMPTY, "x",
                "name", "a", "b", new Origin("a", "1"), new Origin("b", "1"));

        assertThat(PropertyRules.classify("view", context)).singleElement().satisfies(change ->
                assertThat(change.compatible()).isFalse());
    }

    @Test
    void comparesEveryComponentOfARecord() {
        assertThat(PropertyRules.compare("entity", new Element("Party", "red"), new Element("Party", "blue"), "Party",
                CompiledSchema.EMPTY, CompiledSchema.EMPTY)).singleElement()
                .satisfies(change -> assertThat(change.compatible()).isFalse());
    }

    @Test
    void everyComponentOfTheDefinitionRecordsHasARule() {
        // A component without a rule is breaking: a new component must be classified deliberately.
        assertThat(rulesMissingFor("attribute", AttributeDefinition.class)).isEmpty();
        assertThat(rulesMissingFor("entity", EntityTypeDefinition.class)).isEmpty();
        assertThat(rulesMissingFor("trait", TraitDefinition.class)).isEmpty();
        assertThat(rulesMissingFor("relationship", RelationshipTypeDefinition.class)).isEmpty();
    }

    @Nested
    class Descriptions {

        @Test
        void addingChangingAndRemovingADescriptionAreCompatible() {
            AttributeDefinition none = attribute(a -> a);
            AttributeDefinition first = attribute(a -> a.description("One"));
            AttributeDefinition second = attribute(a -> a.description("Two"));

            assertThat(compare(none, first)).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isTrue();
                assertThat(change.description()).contains("added the description");
            });
            assertThat(compare(first, second)).singleElement().satisfies(change ->
                    assertThat(change.description()).contains("changed the description"));
            assertThat(compare(second, none)).singleElement().satisfies(change ->
                    assertThat(change.description()).contains("removed the description"));
        }

        @Test
        void descriptionsOfTypesAreCompatibleToo() {
            EntityTypeDefinition described = entity(e -> e.description("A party"));

            assertThat(PropertyRules.compare("entity", entity(e -> e), described, "Party", CompiledSchema.EMPTY,
                    CompiledSchema.EMPTY)).singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isTrue();
                        assertThat(change.path()).isEqualTo("Party");
                    });
        }

        @Test
        void examplesAreAlwaysCompatible() {
            assertThat(compare(attribute(a -> a.examples(Value.of("a"))), attribute(a -> a.examples(Value.of("b")))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a), attribute(a -> a.examples(Value.of("b")))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a.examples(Value.of("b"))), attribute(a -> a)))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
        }
    }

    @Nested
    class AllowedValues {

        @Test
        void addingAValueToExistingOnesIsCompatible() {
            assertThat(compare(attribute(a -> a.allowedValues("Corporate", "Retail")),
                    attribute(a -> a.allowedValues("Corporate", "Retail", "Private"))))
                    .singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isTrue();
                        assertThat(change.description()).contains("Private");
                    });
        }

        @Test
        void removingAllValuesIsCompatible() {
            assertThat(compare(attribute(a -> a.allowedValues("Corporate", "Retail")), attribute(a -> a)))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
        }

        @Test
        void removingAValueIsBreakingWithAHint() {
            assertThat(compare(attribute(a -> a.allowedValues("Corporate", "Retail")),
                    attribute(a -> a.allowedValues("Corporate"))))
                    .singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.description()).contains("Retail");
                        assertThat(change.hint()).contains(Changes.KEEP_UNCONSTRAINED);
                    });
        }

        @Test
        void replacingAValueIsBreaking() {
            assertThat(compare(attribute(a -> a.allowedValues("Corporate", "Retail")),
                    attribute(a -> a.allowedValues("Corporate", "Private"))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
        }

        @Test
        void givingValuesToAnAttributeThatHadNoneIsBreaking() {
            assertThat(compare(attribute(a -> a), attribute(a -> a.allowedValues("Corporate"))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
        }

        @Test
        void listingTheSameValuesInAnotherOrderIsNoChange() {
            assertThat(compare(attribute(a -> a.allowedValues("Corporate", "Retail")),
                    attribute(a -> a.allowedValues("Retail", "Corporate")))).isEmpty();
        }
    }

    @Nested
    class Pattern {

        @Test
        void removingAPatternIsCompatible() {
            assertThat(compare(attribute(a -> a.pattern("[A-Z]{3}")), attribute(a -> a)))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
        }

        @Test
        void addingOrChangingAPatternIsBreakingWithAHint() {
            assertThat(compare(attribute(a -> a), attribute(a -> a.pattern("[A-Z]{3}"))))
                    .singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.hint()).isPresent();
                    });
            assertThat(compare(attribute(a -> a.pattern("[A-Z]{3}")), attribute(a -> a.pattern("[A-Z]{2,3}"))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
        }
    }

    @Nested
    class Lengths {

        @Test
        void loweringOrRemovingAMinimumIsCompatibleAndRaisingOrAddingIsBreaking() {
            assertThat(compare(attribute(a -> a.minLength(5)), attribute(a -> a.minLength(3))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a.minLength(5)), attribute(a -> a)))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a.minLength(3)), attribute(a -> a.minLength(5))))
                    .singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.hint()).isPresent();
                    });
            assertThat(compare(attribute(a -> a), attribute(a -> a.minLength(1))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
        }

        @Test
        void raisingOrRemovingAMaximumIsCompatibleAndLoweringOrAddingIsBreaking() {
            assertThat(compare(attribute(a -> a.maxLength(3)), attribute(a -> a.maxLength(5))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a.maxLength(3)), attribute(a -> a)))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(compare(attribute(a -> a.maxLength(5)), attribute(a -> a.maxLength(3))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
            assertThat(compare(attribute(a -> a), attribute(a -> a.maxLength(50))))
                    .singleElement().satisfies(change -> assertThat(change.compatible()).isFalse());
        }
    }

    @Nested
    class Rules {

        private final RuleReference lei = new RuleReference("lei-checksum", 1);
        private final RuleReference notBlank = new RuleReference("name-not-blank", 1);

        @Test
        void removingARuleIsCompatibleAndLocatedAtTheRule() {
            assertThat(rules(List.of(lei, notBlank), List.of(notBlank))).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isTrue();
                assertThat(change.path()).isEqualTo("Organization.lei-checksum");
            });
        }

        @Test
        void addingARuleIsBreakingWithAHintAndLocatedAtTheRule() {
            assertThat(rules(List.of(notBlank), List.of(notBlank, lei))).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isFalse();
                assertThat(change.path()).isEqualTo("Organization.lei-checksum");
                assertThat(change.hint()).isPresent();
            });
        }

        @Test
        void changingTheVersionOfARuleIsOneBreakingChange() {
            assertThat(rules(List.of(lei), List.of(new RuleReference("lei-checksum", 2)))).singleElement()
                    .satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.description()).contains("version", "1", "2");
                    });
        }

        @Test
        void everyAddedRuleIsReported() {
            assertThat(rules(List.of(), List.of(lei, notBlank))).hasSize(2).allMatch(change -> !change.compatible());
        }

        @Test
        void listingTheSameRulesInAnotherOrderIsNoChange() {
            assertThat(rules(List.of(lei, notBlank), List.of(notBlank, lei))).isEmpty();
        }
    }

    @Nested
    class Checks {

        private final Check balance = new Check("balance-not-negative", "self.balance >= 0.0");
        private final Check notEmpty = new Check("name-not-empty", "size(self.name) > 0");

        @Test
        void removingACheckIsCompatibleAndLocatedAtTheCheck() {
            assertThat(checks(List.of(balance, notEmpty), List.of(notEmpty))).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isTrue();
                assertThat(change.path()).isEqualTo("Account.balance-not-negative");
                assertThat(change.description()).contains("removed", "balance-not-negative");
            });
        }

        @Test
        void addingACheckIsBreakingWithAHintAndLocatedAtTheCheck() {
            assertThat(checks(List.of(notEmpty), List.of(notEmpty, balance))).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isFalse();
                assertThat(change.path()).isEqualTo("Account.balance-not-negative");
                assertThat(change.hint()).isPresent();
            });
        }

        @Test
        void changingTheExpressionIsBreakingAndNamesBoth() {
            Check changed = new Check("balance-not-negative", "self.balance > 0.0");

            assertThat(checks(List.of(balance), List.of(changed))).singleElement().satisfies(change -> {
                assertThat(change.compatible()).isFalse();
                assertThat(change.path()).isEqualTo("Account.balance-not-negative");
                assertThat(change.description()).contains("self.balance >= 0.0", "self.balance > 0.0");
            });
        }

        @Test
        void addingChangingOrRemovingOnlyTheMessageIsCompatible() {
            Check described = new Check(balance.name(), balance.expression(), Optional.of("No overdrafts."));
            Check reworded = new Check(balance.name(), balance.expression(), Optional.of("A balance cannot be negative."));

            assertThat(checks(List.of(balance), List.of(described))).singleElement()
                    .satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(checks(List.of(described), List.of(reworded))).singleElement()
                    .satisfies(change -> assertThat(change.compatible()).isTrue());
            assertThat(checks(List.of(reworded), List.of(balance))).singleElement()
                    .satisfies(change -> assertThat(change.compatible()).isTrue());
        }

        @Test
        void changingTheExpressionAndTheMessageTogetherIsBreaking() {
            Check changed = new Check(balance.name(), "self.balance > 0.0", Optional.of("Positive balances only."));

            assertThat(checks(List.of(balance), List.of(changed))).singleElement()
                    .satisfies(change -> assertThat(change.compatible()).isFalse());
        }

        @Test
        void everyAddedCheckIsReported() {
            assertThat(checks(List.of(), List.of(balance, notEmpty))).hasSize(2)
                    .allMatch(change -> !change.compatible());
        }

        @Test
        void listingTheSameChecksInAnotherOrderIsNoChange() {
            assertThat(checks(List.of(balance, notEmpty), List.of(notEmpty, balance))).isEmpty();
        }

        @Test
        void relationshipTypesAreComparedTheSame() {
            RelationshipTypeDefinition before = relationshipWithChecks(List.of(balance));
            RelationshipTypeDefinition after = relationshipWithChecks(List.of(balance, notEmpty));

            assertThat(PropertyRules.compare("relationship", before, after, "Employs", CompiledSchema.EMPTY,
                    CompiledSchema.EMPTY)).singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.path()).isEqualTo("Employs.name-not-empty");
                    });
            assertThat(PropertyRules.compare("relationship", after, before, "Employs", CompiledSchema.EMPTY,
                    CompiledSchema.EMPTY)).singleElement()
                    .satisfies(change -> assertThat(change.compatible()).isTrue());
        }

        @Test
        void checksHaveARuleOfTheirOwnForEntityAndRelationshipTypes() {
            assertThat(PropertyRules.hasRule("entity", "checks")).isTrue();
            assertThat(PropertyRules.hasRule("relationship", "checks")).isTrue();
        }
    }

    @Nested
    class OutlivingTargets {

        @Test
        void declaringItIsCompatibleAndGivingItUpIsBreakingWithAHint() {
            RelationshipTypeDefinition without = relationship(false);
            RelationshipTypeDefinition with = relationship(true);

            assertThat(PropertyRules.compare("relationship", without, with, "OpenedBy", CompiledSchema.EMPTY,
                    CompiledSchema.EMPTY)).singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isTrue();
                        assertThat(change.path()).isEqualTo("OpenedBy");
                    });
            assertThat(PropertyRules.compare("relationship", with, without, "OpenedBy", CompiledSchema.EMPTY,
                    CompiledSchema.EMPTY)).singleElement().satisfies(change -> {
                        assertThat(change.compatible()).isFalse();
                        assertThat(change.path()).isEqualTo("OpenedBy");
                        assertThat(change.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("outlivesTarget"));
                    });
        }

        @Test
        void aRelationshipTypeThatStaysAsItIsHasNoChange() {
            assertThat(PropertyRules.compare("relationship", relationship(true), relationship(true), "OpenedBy",
                    CompiledSchema.EMPTY, CompiledSchema.EMPTY)).isEmpty();
        }

        @Test
        void outlivingTargetsHasARuleOfItsOwn() {
            assertThat(PropertyRules.hasRule("relationship", "outlivesTarget")).isTrue();
        }

        private RelationshipTypeDefinition relationship(boolean outlivesTarget) {
            return new RelationshipTypeDefinition("OpenedBy", "Account", "Person", Multiplicity.ZERO_OR_ONE,
                    Multiplicity.ZERO_OR_MORE, "opened", List.of(), RelationshipKind.ASSOCIATION, false, outlivesTarget,
                    Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of(), List.of());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<SchemaChange> checks(List<Check> before, List<Check> after) {
        return PropertyRules.compare("entity", withChecks(before), withChecks(after), "Account", CompiledSchema.EMPTY,
                CompiledSchema.EMPTY);
    }

    private static EntityTypeDefinition withChecks(List<Check> checks) {
        return new EntityTypeDefinition("Account", false, Optional.empty(), List.of(), List.of(), List.of(), false,
                Optional.empty(), List.of(), checks);
    }

    private static RelationshipTypeDefinition relationshipWithChecks(List<Check> checks) {
        return new RelationshipTypeDefinition("Employs", "Organization", "Person", Multiplicity.ZERO_OR_MORE,
                Multiplicity.ZERO_OR_MORE, "employedBy", List.of(), RelationshipKind.ASSOCIATION, false,
                Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of(), checks);
    }

    private static List<String> rulesMissingFor(String elementKind, Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(property -> !PropertyRules.hasRule(elementKind, property))
                .toList();
    }

    private static AttributeDefinition attribute(UnaryOperator<AttributeBuilder> spec) {
        return spec.apply(AttributeDefinition.builder("code", ValueType.STRING)).build();
    }

    private static List<SchemaChange> compare(AttributeDefinition oldAttribute, AttributeDefinition newAttribute) {
        return PropertyRules.compare("attribute", oldAttribute, newAttribute, "Party.code", CompiledSchema.EMPTY,
                CompiledSchema.EMPTY);
    }

    private static EntityTypeDefinition entity(UnaryOperator<EntityTypeBuilder> spec) {
        return SchemaDefinition.builder().entityType("Party", spec::apply).build().entityTypes().getFirst();
    }

    private static List<SchemaChange> rules(List<RuleReference> before, List<RuleReference> after) {
        return PropertyRules.compare("entity", withRules("Organization", before), withRules("Organization", after),
                "Organization", CompiledSchema.EMPTY, CompiledSchema.EMPTY);
    }

    private static EntityTypeDefinition withRules(String name, List<RuleReference> rules) {
        return new EntityTypeDefinition(name, false, Optional.empty(), List.of(), List.of(), List.of(), false,
                Optional.empty(), rules);
    }
}
