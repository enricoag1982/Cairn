package io.github.enricoag1982.cairn.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefinitionRecordsTest {

    @Test
    void constructorsRejectNulls() {
        assertThatThrownBy(() -> new AttributeDefinition(null, ValueType.STRING, false, false, Optional.empty(),
                List.of(), false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AttributeDefinition("name", null, false, false, Optional.empty(),
                List.of(), false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityTypeDefinition("Party", false, null, List.of(), List.of(), List.of(),
                false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraitDefinition(null, List.of(), List.of(), false))
                .isInstanceOf(NullPointerException.class);
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
        EntityTypeDefinition party = new EntityTypeDefinition(
                "Party", true, Optional.empty(), List.of(), List.of(), aliases, false);
        aliases.add("Somebody");

        assertThat(party.aliases()).containsExactly("Actor");
        assertThatThrownBy(() -> party.aliases().add("Other")).isInstanceOf(UnsupportedOperationException.class);
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
