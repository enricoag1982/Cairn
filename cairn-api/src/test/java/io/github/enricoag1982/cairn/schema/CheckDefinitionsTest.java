package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The checks of type definitions: the {@link Check} record, the builders, and the problem codes (capability
 * {@code checks}).
 */
class CheckDefinitionsTest {

    @Test
    void aCheckHasANameAnExpressionAndAnOptionalMessage() {
        Check bare = new Check("balance-not-negative", "self.balance >= 0.0");
        Check described = new Check("balance-not-negative", "self.balance >= 0.0",
                Optional.of("A balance cannot be negative."));

        assertThat(bare.message()).isEmpty();
        assertThat(described.message()).contains("A balance cannot be negative.");
        assertThat(bare).isNotEqualTo(described);
        assertThat(bare).isEqualTo(new Check("balance-not-negative", "self.balance >= 0.0", Optional.empty()));
        assertThat(Check.MAXIMUM_EXPRESSION_LENGTH).isEqualTo(4_000);
    }

    @Test
    void aCheckRejectsNulls() {
        assertThatThrownBy(() -> new Check(null, "true")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Check("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Check("a", "true", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EffectiveCheck(null, "Party")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EffectiveCheck(new Check("a", "true"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCheckIsNotValidatedWhenItIsBuilt() {
        Check strange = new Check("Not_A_Valid-Name", " ");

        assertThat(strange.name()).isEqualTo("Not_A_Valid-Name");
        assertThat(strange.expression()).isEqualTo(" ");
    }

    @Test
    void definitionsWithoutChecksKeepTheirOldConstructors() {
        EntityTypeDefinition entity = new EntityTypeDefinition("Party", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, Optional.empty(), List.of());
        RelationshipTypeDefinition relationship = new RelationshipTypeDefinition("Owns", "Party", "Party",
                Multiplicity.ZERO_OR_MORE, Multiplicity.ZERO_OR_MORE, "ownedBy", List.of(), RelationshipKind.ASSOCIATION,
                false, Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of());

        assertThat(entity.checks()).isEmpty();
        assertThat(relationship.checks()).isEmpty();
        assertThat(entity).isEqualTo(new EntityTypeDefinition("Party", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, Optional.empty(), List.of(), List.of()));
    }

    @Test
    void theChecksOfADefinitionAreCopiedAndUnmodifiable() {
        List<Check> checks = new ArrayList<>(List.of(new Check("a", "true")));
        EntityTypeDefinition entity = new EntityTypeDefinition("Party", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, Optional.empty(), List.of(), checks);
        checks.add(new Check("b", "true"));

        assertThat(entity.checks()).containsExactly(new Check("a", "true"));
        assertThatThrownBy(() -> entity.checks().add(new Check("c", "true")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new EntityTypeDefinition("Party", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, Optional.empty(), List.of(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildersDeclareChecksWithAndWithoutAMessage() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Account", e -> e.attribute("balance", ValueType.DECIMAL)
                        .check("balance-not-negative", "self.balance >= 0.0", "A balance cannot be negative.")
                        .check("balance-small", "self.balance < 1000000.0"))
                .relationshipType("Employs", r -> r.from("Organization").to("Person").inverse("employedBy")
                        .attribute("title", ValueType.STRING)
                        .check("title-not-blank", "!has(self.title) || size(self.title) > 0")
                        .check("title-short", "!has(self.title) || size(self.title) < 50", "The title is too long."))
                .build();

        assertThat(definition.entityTypes().getFirst().checks()).containsExactly(
                new Check("balance-not-negative", "self.balance >= 0.0", Optional.of("A balance cannot be negative.")),
                new Check("balance-small", "self.balance < 1000000.0", Optional.empty()));
        assertThat(definition.relationshipTypes().getFirst().checks()).containsExactly(
                new Check("title-not-blank", "!has(self.title) || size(self.title) > 0", Optional.empty()),
                new Check("title-short", "!has(self.title) || size(self.title) < 50",
                        Optional.of("The title is too long.")));
        assertThatThrownBy(() -> SchemaDefinition.builder().entityType("A", e -> e.check("a", "true", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theResolvedTypesCopyTheirChecks() {
        List<Check> declared = new ArrayList<>(List.of(new Check("a", "true")));
        List<EffectiveCheck> effective = new ArrayList<>(List.of(new EffectiveCheck(declared.getFirst(), "Party")));
        EntityType type = new EntityType(new TypeId(1), "Party", List.of(), false, false, Optional.empty(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), declared, effective,
                new Origin("model", "1"));
        declared.clear();
        effective.clear();

        assertThat(type.checks()).hasSize(1);
        assertThat(type.effectiveChecks()).containsExactly(new EffectiveCheck(new Check("a", "true"), "Party"));
        assertThatThrownBy(() -> type.effectiveChecks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theCodesOfChecksAreExplained() {
        assertThat(ProblemCode.INVALID_CHECK_NAME.explanation()).contains("lowercase");
        assertThat(ProblemCode.DUPLICATE_CHECK.explanation()).contains("subtypes");
        assertThat(ProblemCode.INVALID_CHECK.explanation()).contains("self").contains("4,000").contains("boolean");
        assertThat(ProblemCode.CHECKS_UNAVAILABLE.explanation()).contains("cairn-cel");
        assertThat(ProblemCode.CHECK_VIOLATED.explanation()).contains("message");
        assertThat(ProblemCode.CHECK_FAILED.explanation()).contains("has(");
    }
}
