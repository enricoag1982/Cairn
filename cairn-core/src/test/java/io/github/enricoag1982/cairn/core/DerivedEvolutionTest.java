package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.core.Results.unchanged;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeTrait;
import static io.github.enricoag1982.cairn.fixtures.Definitions.plus;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withAttributes;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How derived attributes evolve, beyond the scenarios of the {@code schema-evolution} spec (design D5 of
 * {@code add-derived-attributes}).
 */
class DerivedEvolutionTest {

    private static final String LABEL = "self.number + ' ' + self.currency";

    private final TestClock clock = new TestClock();
    private final SchemaRegistry registry = SchemaRegistries.inMemory(clock, ReferenceModel.rules());

    private RegistrationResult register(String version, SchemaDefinition definition) {
        clock.advance(Duration.ofMinutes(1));
        return registry.register(ReferenceModel.origin(version), definition);
    }

    private static SchemaDefinition withLabel(String expression, boolean required) {
        return withDerivedAttribute(ReferenceModel.definition(), "Account", "label", ValueType.STRING, required,
                expression);
    }

    private static SchemaDefinition withPlain(String name) {
        return changeEntityType(ReferenceModel.definition(), "Account", type -> {
            List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
            attributes.add(AttributeDefinition.builder(name, ValueType.STRING).build());
            return withAttributes(type, attributes);
        });
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    @Test
    void anOptionalDerivedAttributeAndARequiredOneAreBothBreakingOnAnExistingType() {
        applied(register("1.0.0", ReferenceModel.definition()));

        Problem optional = only(rejected(register("1.1.0", withLabel(LABEL, false))));
        Problem required = only(rejected(register("1.1.0", withLabel(LABEL, true))));

        assertThat(optional.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(optional.path()).isEqualTo("Account.label");
        assertThat(optional.message()).contains("derived attribute label");
        assertThat(optional.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("together with its type"));
        assertThat(required.path()).isEqualTo("Account.label");
    }

    @Test
    void aDerivedAttributeOnANewTypeIsCompatible() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withNewType = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .entityType("Wallet", e -> e.attribute("balance", ValueType.DECIMAL)
                        .attribute("cents", ValueType.LONG, a -> a.required().derived("int(self.balance * 100.0)")))
                .build());

        applied(register("1.1.0", withNewType));

        assertThat(registry.current().entityType("Wallet").orElseThrow().attributes()).hasSize(2);
    }

    @Test
    void aDerivedAttributeThatNoTypeWithDataHasIsCompatible() {
        applied(register("1.0.0", ReferenceModel.definition()));
        SchemaDefinition withTrait = plus(ReferenceModel.definition(), SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING, a -> a.derived("'x'"))).build());

        applied(register("1.1.0", withTrait));

        assertThat(registry.current().trait("Labelled").orElseThrow().attributes()).hasSize(1);
    }

    @Test
    void includingATraitWithADerivedAttributeInAnExistingTypeIsBreaking() {
        SchemaDefinition trait = SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING, a -> a.derived("self.number"))).build();
        applied(register("1.0.0", plus(ReferenceModel.definition(), trait)));
        SchemaDefinition included = changeEntityType(plus(ReferenceModel.definition(), trait), "Account", type ->
                new EntityTypeDefinition(type.name(), type.isAbstract(),
                        type.supertype(), List.of("Labelled"), type.attributes(), type.aliases(), type.deprecated(),
                        type.description(), type.rules(), type.checks()));

        Problem problem = only(rejected(register("1.1.0", included)));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Labelled.label");
        assertThat(problem.message()).contains("derived attribute label").contains("Account");
    }

    @Test
    void makingAPlainAttributeDerivedIsBreaking() {
        applied(register("1.0.0", withPlain("label")));

        Problem problem = only(rejected(register("1.1.0", withLabel(LABEL, false))));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Account.label");
        assertThat(problem.message()).contains("made the attribute derived").contains(LABEL);
    }

    @Test
    void changingTheExpressionOfADerivedAttributeIsBreaking() {
        applied(register("1.0.0", withLabel(LABEL, false)));

        Problem problem = only(rejected(register("1.1.0", withLabel("self.number", false))));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Account.label");
        assertThat(problem.message()).contains("changed the expression").contains(LABEL).contains("self.number");
    }

    @Test
    void theSameExpressionIsNoChange() {
        applied(register("1.0.0", withLabel(LABEL, false)));

        unchanged(register("1.0.0", withLabel(LABEL, false)));
        unchanged(register("1.1.0", withLabel(LABEL, false)));
    }

    @Test
    void removingADerivedAttributeIsRemovingAnAttribute() {
        applied(register("1.0.0", withLabel(LABEL, false)));

        Problem problem = only(rejected(register("1.1.0", ReferenceModel.definition())));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Account.label");
        assertThat(problem.message()).contains("removed the attribute label");
    }

    @Test
    void makingADerivedAttributePlainIsCompatibleAndThePlanSaysSo() {
        applied(register("1.0.0", withLabel(LABEL, true)));

        SchemaPlan plan = registry.plan(ReferenceModel.origin("1.1.0"), changeEntityType(ReferenceModel.definition(),
                "Account", type -> {
                    List<AttributeDefinition> attributes = new ArrayList<>(type.attributes());
                    attributes.add(AttributeDefinition.builder("label", ValueType.STRING).required().build());
                    return withAttributes(type, attributes);
                }));

        assertThat(plan.outcome()).isEqualTo(SchemaPlan.Outcome.APPLY);
        assertThat(plan.changes()).filteredOn(change -> change.path().equals("Account.label"))
                .singleElement().satisfies(change -> {
                    assertThat(change.compatible()).isTrue();
                    assertThat(change.description()).contains("plain").contains("stored values");
                });
        assertThat(plan.changes()).extracting(SchemaChange::compatible).doesNotContain(false);
    }

    @Test
    void movingAnOptionalDerivedAttributeToASupertypeIsBreakingWhereAnotherExistingTypeGainsIt() {
        AttributeDefinition tag = AttributeDefinition.builder("tag", ValueType.STRING).derived("self.name").build();
        applied(register("1.0.0", changeEntityType(ReferenceModel.definition(), "Person",
                type -> withAttributes(type, appended(type.attributes(), tag)))));
        SchemaDefinition moved = changeEntityType(ReferenceModel.definition(), "Party",
                type -> withAttributes(type, List.of(tag)));

        Problem problem = only(rejected(register("1.1.0", moved)));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Party.tag");
        assertThat(problem.message()).contains("derived attribute tag").contains("Organization");
    }

    private static List<AttributeDefinition> appended(List<AttributeDefinition> attributes, AttributeDefinition added) {
        List<AttributeDefinition> result = new ArrayList<>(attributes);
        result.add(added);
        return result;
    }

    @Test
    void aDerivedAttributeThatBecomesDerivedInATraitIsBreakingForTheTypesThatIncludeIt() {
        SchemaDefinition plainTrait = SchemaDefinition.builder()
                .trait("Labelled", t -> t.attribute("label", ValueType.STRING)).build();
        SchemaDefinition model = changeEntityType(plus(ReferenceModel.definition(), plainTrait), "Account", type ->
                new EntityTypeDefinition(type.name(), type.isAbstract(),
                        type.supertype(), List.of("Labelled"), type.attributes(), type.aliases(), type.deprecated(),
                        type.description(), type.rules(), type.checks()));
        applied(register("1.0.0", model));
        SchemaDefinition derived = changeTrait(model, "Labelled", trait -> new TraitDefinition(trait.name(), List.of(AttributeDefinition.builder("label", ValueType.STRING)
                        .derived("'x'").build()), trait.aliases(), trait.deprecated(), trait.description()));

        Problem problem = only(rejected(register("1.1.0", derived)));

        assertThat(problem.code()).isEqualTo(ProblemCode.BREAKING_CHANGE);
        assertThat(problem.path()).isEqualTo("Labelled.label");
    }
}
