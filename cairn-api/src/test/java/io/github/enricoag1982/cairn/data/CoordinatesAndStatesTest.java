package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoordinatesAndStatesTest {

    private static final Instant MARCH_31 = Instant.parse("2026-03-31T00:00:00Z");
    private static final Instant APRIL_5 = Instant.parse("2026-04-05T00:00:00Z");

    @Test
    void coordinatesReadAsOfTheLatestBasisUnlessToldOtherwise() {
        Coordinates coordinates = Coordinates.effectiveAt(MARCH_31);

        assertThat(coordinates.effectiveAt()).isEqualTo(MARCH_31);
        assertThat(coordinates.knownAt()).isEqualTo(KnownAt.latest());
    }

    @Test
    void coordinatesCanBeKnownAtABasisOrATransactionTime() {
        Coordinates base = Coordinates.effectiveAt(MARCH_31);

        assertThat(base.knownAt(new Basis(4)).knownAt()).isEqualTo(new KnownAt.AtBasis(new Basis(4)));
        assertThat(base.knownAt(APRIL_5).knownAt()).isEqualTo(new KnownAt.AtTransactionTime(APRIL_5));
        assertThat(base.knownAt(KnownAt.basis(new Basis(4)))).isEqualTo(base.knownAt(new Basis(4)));
        assertThat(base.knownAt(APRIL_5).effectiveAt()).isEqualTo(MARCH_31);
    }

    @Test
    void knownAtReturnsNewCoordinatesAndLeavesTheOldOnesAlone() {
        Coordinates base = Coordinates.effectiveAt(MARCH_31);

        base.knownAt(APRIL_5);

        assertThat(base.knownAt()).isEqualTo(KnownAt.latest());
    }

    @Test
    void theEffectiveTimeIsTruncatedToMicroseconds() {
        Coordinates coordinates = Coordinates.effectiveAt(Instant.parse("2026-03-31T10:15:30.123456789Z"));

        assertThat(coordinates.effectiveAt()).isEqualTo(Instant.parse("2026-03-31T10:15:30.123456Z"));
    }

    @Test
    void coordinatesRejectNulls() {
        assertThatThrownBy(() -> Coordinates.effectiveAt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Coordinates(MARCH_31, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KnownAt.basis(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KnownAt.transactionTime(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anEntityStateKeepsTheOrderOfItsValues() {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("name", Value.of("A-1"));
        values.put("currency", Value.of("EUR"));
        values.put("balance", Value.of(1000));

        EntityState state = state(values);
        values.clear();

        assertThat(state.values().keySet()).containsExactly("name", "currency", "balance");
        assertThatThrownBy(() -> state.values().put("x", Value.of(1))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anEntityStateRejectsNulls() {
        EffectivePeriod period = EffectivePeriod.from(MARCH_31);
        Instant recorded = APRIL_5;
        EntityId id = new EntityId("a");

        assertThatThrownBy(() -> new EntityState(null, "T", Map.of(), period, recorded, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityState(id, null, Map.of(), period, recorded, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityState(id, "T", null, period, recorded, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityState(id, "T", Map.of(), null, recorded, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityState(id, "T", Map.of(), period, null, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityState(id, "T", Map.of(), period, recorded, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRelationshipStateKeepsTheOrderOfItsValues() {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("share", Value.of(60));
        values.put("note", Value.of("direct"));

        RelationshipState state = relationship(values);
        values.clear();

        assertThat(state.values().keySet()).containsExactly("share", "note");
        assertThatThrownBy(() -> state.values().put("x", Value.of(1))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRelationshipStateHoldsItsTypeEndsAndRecording() {
        RelationshipState state = relationship(Map.of("share", Value.of(60)));

        assertThat(state.id()).isEqualTo(new RelationshipId("r"));
        assertThat(state.relationshipType()).isEqualTo("Owns");
        assertThat(state.source()).isEqualTo(new EntityId("acme"));
        assertThat(state.target()).isEqualTo(new EntityId("beta"));
        assertThat(state.period()).isEqualTo(EffectivePeriod.from(MARCH_31));
        assertThat(state.recordedAt()).isEqualTo(APRIL_5);
        assertThat(state.recordedBasis()).isEqualTo(new Basis(2));
    }

    @Test
    void aRelationshipStateRejectsNulls() {
        EffectivePeriod period = EffectivePeriod.from(MARCH_31);
        RelationshipId id = new RelationshipId("r");
        EntityId acme = new EntityId("acme");
        EntityId beta = new EntityId("beta");

        assertThatThrownBy(() -> new RelationshipState(null, "Owns", acme, beta, Map.of(), period, APRIL_5,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, null, acme, beta, Map.of(), period, APRIL_5,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", null, beta, Map.of(), period, APRIL_5,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", acme, null, Map.of(), period, APRIL_5,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", acme, beta, null, period, APRIL_5, new Basis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", acme, beta, Map.of(), null, APRIL_5,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", acme, beta, Map.of(), period, null,
                new Basis(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipState(id, "Owns", acme, beta, Map.of(), period, APRIL_5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aLinkHoldsTheRelationshipAndTheEntityAtItsOtherEnd() {
        RelationshipState relationship = relationship(Map.of("share", Value.of(60)));
        EntityState acme = acme();

        Link link = new Link(relationship, acme);

        assertThat(link.relationship()).isSameAs(relationship);
        assertThat(link.entity()).isSameAs(acme);
        assertThatThrownBy(() -> new Link(null, acme)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Link(relationship, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aStepHoldsTheEntityItsDepthAndTheRelationshipItWasReachedThrough() {
        RelationshipState relationship = relationship(Map.of("share", Value.of(60)));
        EntityState acme = acme();

        Step step = new Step(acme, 1, relationship);

        assertThat(step.entity()).isSameAs(acme);
        assertThat(step.depth()).isEqualTo(1);
        assertThat(step.via()).isSameAs(relationship);
        assertThat(new Step(acme, 7, relationship).depth()).isEqualTo(7);
    }

    @Test
    void aStepNeedsAnEntityARelationshipAndADepthOfAtLeastOne() {
        RelationshipState relationship = relationship(Map.of());
        EntityState acme = acme();

        assertThatThrownBy(() -> new Step(null, 1, relationship)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Step(acme, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Step(acme, 0, relationship)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
        assertThatThrownBy(() -> new Step(acme, -1, relationship)).isInstanceOf(IllegalArgumentException.class);
    }

    private static EntityState acme() {
        return new EntityState(new EntityId("acme"), "Organization", Map.of(), EffectivePeriod.from(MARCH_31),
                APRIL_5, new Basis(2));
    }

    private static RelationshipState relationship(Map<String, Value> values) {
        return new RelationshipState(new RelationshipId("r"), "Owns", new EntityId("acme"), new EntityId("beta"),
                values, EffectivePeriod.from(MARCH_31), APRIL_5, new Basis(2));
    }

    private static EntityState state(Map<String, Value> values) {
        return new EntityState(new EntityId("a"), "Account", values, EffectivePeriod.from(MARCH_31), APRIL_5,
                new Basis(2));
    }
}
