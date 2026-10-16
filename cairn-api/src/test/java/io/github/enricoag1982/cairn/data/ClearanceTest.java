package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Clearances (ADR-0036, design D2 of {@code add-attribute-access-control}). */
class ClearanceTest {

    private static Attribute attribute(String name, String classification) {
        return new Attribute(new AttributeId(1), name, List.of(), ValueType.STRING, false, false, Optional.empty(), false,
                Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
                Optional.empty(), "Employment", Optional.ofNullable(classification));
    }

    @Test
    void aClearanceHoldsTheClassificationsItWasGivenInTheirOrderAndOnce() {
        Clearance clearance = Clearance.of("pay", "identity", "pay");

        assertThat(clearance.classifications()).containsExactly("pay", "identity");
    }

    @Test
    void noneCoversNoClassification() {
        assertThat(Clearance.none().classifications()).isEmpty();
        assertThat(Clearance.none()).isEqualTo(Clearance.of()).isSameAs(Clearance.none());
    }

    @Test
    void anAttributeWithoutAClassificationIsCoveredByEveryClearance() {
        Attribute plain = attribute("number", null);

        assertThat(Clearance.none().covers(plain)).isTrue();
        assertThat(Clearance.of("pay").covers(plain)).isTrue();
    }

    @Test
    void anAttributeWithAClassificationIsCoveredOnlyByAClearanceThatHoldsIt() {
        Attribute salary = attribute("annualSalary", "pay");

        assertThat(Clearance.none().covers(salary)).isFalse();
        assertThat(Clearance.of("identity").covers(salary)).isFalse();
        assertThat(Clearance.of("identity", "pay").covers(salary)).isTrue();
        assertThat(Clearance.of("Pay").covers(salary)).as("classifications are compared exactly").isFalse();
    }

    @Test
    void intersectingKeepsWhatBothCoverInTheOrderOfTheFirst() {
        Clearance first = Clearance.of("pay", "identity", "finance");
        Clearance second = Clearance.of("finance", "pay", "ops");

        assertThat(first.intersect(second).classifications()).containsExactly("pay", "finance");
        assertThat(second.intersect(first)).isEqualTo(first.intersect(second));
        assertThat(first.intersect(Clearance.none())).isEqualTo(Clearance.none());
        assertThat(first.intersect(first)).isEqualTo(first);
        assertThat(first.intersect(Clearance.of("ops"))).isEqualTo(Clearance.none());
    }

    @Test
    void theClassificationsAreCopiedAndCannotBeChanged() {
        Set<String> given = new HashSet<>(Set.of("pay"));
        Clearance clearance = new Clearance(given);

        given.add("identity");

        assertThat(clearance.classifications()).containsExactly("pay");
        assertThatThrownBy(() -> clearance.classifications().add("identity"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void clearancesWithTheSameClassificationsAreEqualWhateverTheirOrder() {
        assertThat(Clearance.of("pay", "identity")).isEqualTo(Clearance.of("identity", "pay"))
                .hasSameHashCodeAs(Clearance.of("identity", "pay"));
        assertThat(Clearance.of("pay")).isNotEqualTo(Clearance.of("identity"));
        assertThat(Clearance.of("pay")).isNotEqualTo(Clearance.none());
    }

    @Test
    void clearancesRejectNulls() {
        assertThatThrownBy(() -> new Clearance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Clearance.of((String[]) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Clearance.of("pay", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Clearance.none().covers(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Clearance.none().intersect(null)).isInstanceOf(NullPointerException.class);
    }
}
