package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Derived attributes in definitions: the expression of {@link AttributeDefinition}, the builder, the resolved
 * {@link Attribute}, and the problem codes (capability {@code derived-attributes}).
 */
class DerivedAttributeDefinitionsTest {

    private static final String EXPRESSION = "self.givenName + ' ' + self.familyName";

    @Test
    void anAttributeIsNotDerivedUnlessItSaysSo() {
        AttributeDefinition plain = AttributeDefinition.builder("givenName", ValueType.STRING).required().build();
        AttributeDefinition derived = AttributeDefinition.builder("fullName", ValueType.STRING).required()
                .derived(EXPRESSION).build();

        assertThat(plain.derived()).isEmpty();
        assertThat(derived.derived()).hasValue(EXPRESSION);
        assertThat(derived.required()).as("a derived attribute keeps its other properties").isTrue();
    }

    @Test
    void theBuilderKeepsTheLastExpressionAndRejectsNull() {
        AttributeDefinition twice = AttributeDefinition.builder("x", ValueType.STRING).derived("'a'").derived("'b'")
                .build();

        assertThat(twice.derived()).hasValue("'b'");
        assertThatThrownBy(() -> AttributeDefinition.builder("x", ValueType.STRING).derived(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theOldConstructorMakesAnAttributeThatIsNotDerived() {
        AttributeDefinition old = new AttributeDefinition("code", ValueType.STRING, true, true, Optional.empty(),
                List.of("kode"), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty());
        AttributeDefinition current = new AttributeDefinition("code", ValueType.STRING, true, true, Optional.empty(),
                List.of("kode"), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty());

        assertThat(old.derived()).isEmpty();
        assertThat(old).isEqualTo(current);
    }

    @Test
    void theExpressionIsPartOfTheDefinition() {
        AttributeDefinition one = AttributeDefinition.builder("x", ValueType.STRING).derived("'a'").build();
        AttributeDefinition other = AttributeDefinition.builder("x", ValueType.STRING).derived("'b'").build();
        AttributeDefinition plain = AttributeDefinition.builder("x", ValueType.STRING).build();

        assertThat(one).isNotEqualTo(other).isNotEqualTo(plain)
                .isEqualTo(AttributeDefinition.builder("x", ValueType.STRING).derived("'a'").build());
    }

    @Test
    void anExpressionIsNotValidatedWhenTheDefinitionIsBuilt() {
        AttributeDefinition strange = AttributeDefinition.builder("x", ValueType.STRING).derived(" ").build();

        assertThat(strange.derived()).hasValue(" ");
    }

    @Test
    void aDefinitionRejectsAnAbsentOptionalForTheExpression() {
        assertThatThrownBy(() -> new AttributeDefinition("x", ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aResolvedAttributeReportsItsExpression() {
        Attribute attribute = new Attribute(new AttributeId(1), "fullName", List.of(), ValueType.STRING, true, false,
                Optional.empty(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.of(EXPRESSION), "Person");

        assertThat(attribute.derived()).hasValue(EXPRESSION);
        assertThat(attribute.declaredBy()).isEqualTo("Person");
    }

    @Test
    void theCodesOfDerivedAttributesAreExplained() {
        assertThat(ProblemCode.INVALID_DERIVATION.explanation()).contains("self").contains("4,000")
                .contains("not derived").contains("null");
        assertThat(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN.explanation()).contains("create").contains("patch")
                .contains("replace").contains("Leave");
        assertThat(ProblemCode.DERIVATION_FAILED.explanation()).contains("has(").contains("10,000").contains("required")
                .contains("range of an int");
    }

    @Test
    void theCodeForAMissingExpressionLanguageCoversChecksAndDerivedAttributes() {
        assertThat(ProblemCode.EXPRESSIONS_UNAVAILABLE.explanation()).contains("cairn-cel").contains("checks")
                .contains("derived attributes");
        assertThat(ProblemCode.valueOf("EXPRESSIONS_UNAVAILABLE")).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
        assertThatThrownBy(() -> ProblemCode.valueOf("CHECKS_UNAVAILABLE")).isInstanceOf(IllegalArgumentException.class);
    }
}
