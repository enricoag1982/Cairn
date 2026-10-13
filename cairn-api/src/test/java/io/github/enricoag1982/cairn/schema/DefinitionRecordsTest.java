package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DefinitionRecordsTest {

    @Test
    void constructorsRejectNulls() {
        assertThatThrownBy(() -> new AttributeDefinition(null, ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AttributeDefinition("name", null, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AttributeDefinition("name", ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, null, List.of(), List.of(), Optional.empty(), OptionalInt.empty(),
                OptionalInt.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AttributeDefinition("name", ValueType.STRING, false, false, Optional.empty(),
                List.of(), false, Optional.empty(), List.of(), List.of(), Optional.empty(), null,
                OptionalInt.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityTypeDefinition("Party", false, null, List.of(), List.of(), List.of(),
                false, Optional.empty(), List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityTypeDefinition("Party", false, Optional.empty(), List.of(), List.of(),
                List.of(), false, null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraitDefinition(null, List.of(), List.of(), false, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuleReference(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuleDescriptor("lei-checksum", 1, "Checks the LEI.", null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuleCode("invalid-check-digits", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchemaChange("Person", "added", true, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchemaPlan(null, List.of(), List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Problem(ProblemCode.INVALID_NAME, "Party", "bad", null, Optional.empty(),
                Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Literal.of((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Bound(null, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Range(null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Literal.of((BigDecimal) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Origin("reference-model", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SchemaDefinition.builder()
                .relationshipType("Owns", r -> r.supertype(null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void listsAreCopiedAndUnmodifiable() {
        List<String> aliases = new ArrayList<>(List.of("Actor"));
        List<RuleReference> rules = new ArrayList<>(List.of(new RuleReference("name-not-blank", 1)));
        EntityTypeDefinition party = new EntityTypeDefinition(
                "Party", true, Optional.empty(), List.of(), List.of(), aliases, false, Optional.empty(), rules);
        aliases.add("Somebody");
        rules.add(new RuleReference("other-rule", 1));

        assertThat(party.aliases()).containsExactly("Actor");
        assertThat(party.rules()).containsExactly(new RuleReference("name-not-blank", 1));
        assertThatThrownBy(() -> party.aliases().add("Other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> party.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constraintAndExampleListsAreCopiedAndUnmodifiable() {
        List<String> allowed = new ArrayList<>(List.of("Corporate"));
        List<Literal> examples = new ArrayList<>(List.of(Literal.of("Corporate")));
        AttributeDefinition segment = new AttributeDefinition("segment", ValueType.STRING, false, false,
                Optional.empty(), List.of(), false, Optional.empty(), examples, allowed, Optional.empty(),
                OptionalInt.empty(), OptionalInt.empty());
        allowed.add("Retail");
        examples.add(Literal.of("Retail"));

        assertThat(segment.allowedValues()).containsExactly("Corporate");
        assertThat(segment.examples()).containsExactly(Literal.of("Corporate"));
        assertThatThrownBy(() -> segment.allowedValues().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> segment.examples().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void descriptorsCopyTheirCodes() {
        List<RuleCode> codes = new ArrayList<>(List.of(new RuleCode("invalid-check-digits", "The check digits differ.")));
        RuleDescriptor descriptor = new RuleDescriptor("lei-checksum", 1, "Checks the LEI.", "Organization", codes);
        codes.clear();

        assertThat(descriptor.codes()).hasSize(1);
        assertThatThrownBy(() -> descriptor.codes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void plansCopyTheirLists() {
        List<SchemaChange> changes = new ArrayList<>(List.of(new SchemaChange("Person", "added", true, Optional.empty())));
        SchemaPlan plan = new SchemaPlan(SchemaPlan.Outcome.APPLY, changes, List.of());
        changes.clear();

        assertThat(plan.changes()).hasSize(1);
        assertThatThrownBy(() -> plan.problems().add(new Problem(ProblemCode.INVALID_NAME, "x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aProblemWithoutDetailsHasNoExpectedFoundOrHint() {
        Problem problem = new Problem(ProblemCode.INVALID_NAME, "1Party", "Bad name.");

        assertThat(problem.expected()).isEmpty();
        assertThat(problem.found()).isEmpty();
        assertThat(problem.hint()).isEmpty();
        assertThat(problem).isEqualTo(new Problem(ProblemCode.INVALID_NAME, "1Party", "Bad name.", Optional.empty(),
                Optional.empty(), Optional.empty()));
    }

    @Test
    void everyProblemCodeHasAnExplanation() {
        assertThat(ProblemCode.values()).allSatisfy(code -> assertThat(code.explanation()).isNotBlank());
    }

    @Test
    void definitionsWithTheSameContentAreEqual() {
        SchemaDefinition first = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().attribute("name", ValueType.STRING, a -> a.required()))
                .build();
        SchemaDefinition second = SchemaDefinition.builder()
                .entityType("Party", e -> e.abstractType().attribute("name", ValueType.STRING, a -> a.required()))
                .build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void literalsKnowTheirValueType() {
        assertThat(Literal.of(1).valueType()).isEqualTo(ValueType.INT);
        assertThat(Literal.of(1L).valueType()).isEqualTo(ValueType.LONG);
        assertThat(Literal.of("Corporate").valueType()).isEqualTo(ValueType.STRING);
        assertThat(Literal.of(true).valueType()).isEqualTo(ValueType.BOOLEAN);
        assertThat(Literal.of(BigDecimal.ONE).valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(Literal.of(LocalDate.of(2026, 1, 1)).valueType()).isEqualTo(ValueType.DATE);
        assertThat(Literal.of(Instant.EPOCH).valueType()).isEqualTo(ValueType.INSTANT);
    }

    @Test
    void onlyNumbersDatesAndInstantsAreOrdered() {
        assertThat(ValueType.STRING.isOrdered()).isFalse();
        assertThat(ValueType.BOOLEAN.isOrdered()).isFalse();
        assertThat(List.of(ValueType.INT, ValueType.LONG, ValueType.DECIMAL, ValueType.DATE, ValueType.INSTANT))
                .allMatch(ValueType::isOrdered);
    }

    @Test
    void rangeFactoriesSetTheRightBounds() {
        Bound zero = Bound.exclusive(Literal.of(0));
        Bound hundred = Bound.inclusive(Literal.of(100));

        assertThat(Range.between(zero, hundred)).isEqualTo(new Range(Optional.of(zero), Optional.of(hundred)));
        assertThat(Range.atLeast(zero).upper()).isEmpty();
        assertThat(Range.atMost(hundred).lower()).isEmpty();
        assertThat(zero.inclusive()).isFalse();
        assertThat(hundred.inclusive()).isTrue();
    }
}
