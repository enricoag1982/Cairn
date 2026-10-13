package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading the values of a state as Java values (design D2 of {@code add-java-type-generation}). */
class TypedValuesTest {

    private static final Instant NOON = Instant.parse("2026-03-01T12:00:00Z");

    private static final Map<String, Value> VALUES = Map.of(
            "name", Value.of("Alice"),
            "active", Value.of(true),
            "children", Value.of(2),
            "visits", Value.of(12_000_000_000L),
            "share", Value.of(new BigDecimal("30.5")),
            "born", Value.of(LocalDate.of(1990, 4, 2)),
            "seen", Value.of(NOON));

    @Test
    void requiredValuesAreReadInTheirJavaTypes() {
        assertThat(TypedValues.requiredString(VALUES, "name")).isEqualTo("Alice");
        assertThat(TypedValues.requiredBoolean(VALUES, "active")).isTrue();
        assertThat(TypedValues.requiredInt(VALUES, "children")).isEqualTo(2);
        assertThat(TypedValues.requiredLong(VALUES, "visits")).isEqualTo(12_000_000_000L);
        assertThat(TypedValues.requiredDecimal(VALUES, "share")).isEqualTo(new BigDecimal("30.5"));
        assertThat(TypedValues.requiredDate(VALUES, "born")).isEqualTo(LocalDate.of(1990, 4, 2));
        assertThat(TypedValues.requiredInstant(VALUES, "seen")).isEqualTo(NOON);
    }

    @Test
    void optionalValuesAreReadInTheirJavaTypes() {
        assertThat(TypedValues.optionalString(VALUES, "name")).contains("Alice");
        assertThat(TypedValues.optionalBoolean(VALUES, "active")).contains(true);
        assertThat(TypedValues.optionalInt(VALUES, "children")).contains(2);
        assertThat(TypedValues.optionalLong(VALUES, "visits")).contains(12_000_000_000L);
        assertThat(TypedValues.optionalDecimal(VALUES, "share")).contains(new BigDecimal("30.5"));
        assertThat(TypedValues.optionalDate(VALUES, "born")).contains(LocalDate.of(1990, 4, 2));
        assertThat(TypedValues.optionalInstant(VALUES, "seen")).contains(NOON);
    }

    @Test
    void anAbsentOptionalValueIsEmpty() {
        Map<String, Value> none = Map.of();

        assertThat(TypedValues.optionalString(none, "name")).isEmpty();
        assertThat(TypedValues.optionalBoolean(none, "active")).isEmpty();
        assertThat(TypedValues.optionalInt(none, "children")).isEmpty();
        assertThat(TypedValues.optionalLong(none, "visits")).isEmpty();
        assertThat(TypedValues.optionalDecimal(none, "share")).isEmpty();
        assertThat(TypedValues.optionalDate(none, "born")).isEmpty();
        assertThat(TypedValues.optionalInstant(none, "seen")).isEmpty();
    }

    @Test
    void aMissingRequiredValueNamesTheAttribute() {
        Map<String, Value> none = Map.of();

        assertThatThrownBy(() -> TypedValues.requiredString(none, "name")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> TypedValues.requiredBoolean(none, "active")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
        assertThatThrownBy(() -> TypedValues.requiredInt(none, "children")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("children");
        assertThatThrownBy(() -> TypedValues.requiredLong(none, "visits")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visits");
        assertThatThrownBy(() -> TypedValues.requiredDecimal(none, "share")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share");
        assertThatThrownBy(() -> TypedValues.requiredDate(none, "born")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("born");
        assertThatThrownBy(() -> TypedValues.requiredInstant(none, "seen")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seen");
    }

    @Test
    void aValueOfAnotherTypeNamesTheAttribute() {
        assertThatThrownBy(() -> TypedValues.requiredString(VALUES, "children"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("children").hasMessageContaining("INT").hasMessageContaining("STRING");
        assertThatThrownBy(() -> TypedValues.optionalDecimal(VALUES, "children"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("children").hasMessageContaining("DECIMAL");
        assertThatThrownBy(() -> TypedValues.requiredLong(VALUES, "children"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("children");
        assertThatThrownBy(() -> TypedValues.optionalInstant(VALUES, "born"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("born");
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> TypedValues.requiredString(null, "name")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TypedValues.optionalString(VALUES, null)).isInstanceOf(NullPointerException.class);
    }
}
