package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The classification of attributes in definitions and in the schema (ADR-0036, design D1 of {@code add-attribute-access-control}). */
class ClassifiedAttributeDefinitionsTest {

    @Test
    void anAttributeHasNoClassificationUnlessItIsGivenOne() {
        assertThat(AttributeDefinition.builder("balance", ValueType.DECIMAL).build().classification()).isEmpty();
        assertThat(AttributeDefinition.builder("balance", ValueType.DECIMAL).classification("finance").build()
                .classification()).hasValue("finance");
    }

    @Test
    void theBuilderKeepsTheLastClassificationAndRejectsNull() {
        AttributeDefinition attribute = AttributeDefinition.builder("balance", ValueType.DECIMAL)
                .classification("finance").classification("pay").build();

        assertThat(attribute.classification()).hasValue("pay");
        assertThatThrownBy(() -> AttributeDefinition.builder("balance", ValueType.DECIMAL).classification(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theClassificationIsNotValidatedWhenTheDefinitionIsBuilt() {
        AttributeDefinition strange = AttributeDefinition.builder("balance", ValueType.DECIMAL)
                .classification("Finance Data").build();

        assertThat(strange.classification()).hasValue("Finance Data");
    }

    @Test
    void theClassificationIsPartOfWhatMakesDefinitionsEqual() {
        AttributeDefinition plain = AttributeDefinition.builder("balance", ValueType.DECIMAL).build();
        AttributeDefinition classified = AttributeDefinition.builder("balance", ValueType.DECIMAL)
                .classification("finance").build();

        assertThat(classified).isNotEqualTo(plain);
        assertThat(classified).isEqualTo(AttributeDefinition.builder("balance", ValueType.DECIMAL)
                .classification("finance").build());
    }

    @Test
    void theConstructorsWithoutAClassificationMakeDefinitionsWithoutOne() {
        AttributeDefinition withoutDerived = new AttributeDefinition("x", ValueType.STRING, false, false,
                Optional.empty(), List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(),
                OptionalInt.empty(), OptionalInt.empty());
        AttributeDefinition withDerived = new AttributeDefinition("x", ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.of("'a'"));
        AttributeDefinition withClassification = new AttributeDefinition("x", ValueType.STRING, false, false,
                Optional.empty(), List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(),
                OptionalInt.empty(), OptionalInt.empty(), Optional.of("'a'"), Optional.of("pay"));

        assertThat(withoutDerived.classification()).isEmpty();
        assertThat(withoutDerived.derived()).isEmpty();
        assertThat(withDerived.classification()).isEmpty();
        assertThat(withDerived.derived()).hasValue("'a'");
        assertThat(withClassification.classification()).hasValue("pay");
        assertThat(withClassification.derived()).hasValue("'a'");
    }

    @Test
    void aDefinitionRejectsAnAbsentOptionalForTheClassification() {
        assertThatThrownBy(() -> new AttributeDefinition("x", ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aResolvedAttributeReportsItsClassification() {
        Attribute classified = new Attribute(new AttributeId(1), "balance", List.of(), ValueType.DECIMAL, true, false,
                Optional.empty(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty(), "Account", Optional.of("finance"));
        Attribute plain = new Attribute(new AttributeId(2), "number", List.of(), ValueType.STRING, true, false,
                Optional.empty(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty(), "Account");

        assertThat(classified.classification()).hasValue("finance");
        assertThat(plain.classification()).isEmpty();
        assertThatThrownBy(() -> new Attribute(new AttributeId(2), "number", List.of(), ValueType.STRING, true, false,
                Optional.empty(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty(), "Account", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theHelperThatClassifiesAnAttributeOfAnyKindOfElementKeepsTheRestOfTheDefinition() {
        SchemaDefinition model = ReferenceModel.definition();

        SchemaDefinition entity = Definitions.withClassification(model, "Account", "balance", "finance");
        SchemaDefinition trait = Definitions.withClassification(model, "Named", "name", "names");
        SchemaDefinition relationship = Definitions.withClassification(model, "Owns", "share", "ownership");

        assertThat(attribute(entity.entityTypes(), "Account", "balance").classification()).hasValue("finance");
        assertThat(entity.entityTypes()).hasSameSizeAs(model.entityTypes());
        assertThat(model.traits().getFirst().attributes().getFirst().classification()).isEmpty();
        assertThat(trait.traits().getFirst().attributes().getFirst().classification()).hasValue("names");
        assertThat(relationship.relationshipTypes().getFirst().attributes().getFirst().classification())
                .hasValue("ownership");
        assertThat(Definitions.withClassification(entity, "Account", "balance", null).entityTypes())
                .isEqualTo(model.entityTypes());
    }

    private static AttributeDefinition attribute(List<EntityTypeDefinition> types, String type, String name) {
        return types.stream().filter(candidate -> candidate.name().equals(type)).findFirst().orElseThrow().attributes()
                .stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theCodesOfClassificationsAreExplained() {
        assertThat(ProblemCode.INVALID_CLASSIFICATION.explanation()).contains("lowercase").contains("derived")
                .contains("two classifications").contains("128");
        assertThat(ProblemCode.ACCESS_DENIED.explanation()).contains("clearance").contains("query").contains("write")
                .contains("unique key").contains("agent tool").contains("Rules, checks and derivations");
        assertThat(ProblemCode.valueOf("INVALID_CLASSIFICATION")).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
        assertThat(ProblemCode.valueOf("ACCESS_DENIED")).isEqualTo(ProblemCode.ACCESS_DENIED);
    }
}
