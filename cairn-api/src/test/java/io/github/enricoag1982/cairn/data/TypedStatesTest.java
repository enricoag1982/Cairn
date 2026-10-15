package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link TypedEntity} and {@link TypedRelationship} give the IDs of the state they hold (design D2). */
class TypedStatesTest {

    private static final Instant JANUARY = Instant.parse("2026-01-01T00:00:00Z");
    private static final EntityId ACME = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final EntityId BETA = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final RelationshipId OWNS = new RelationshipId("00000000-0000-7000-8000-000000000003");

    @Test
    void aTypedEntityTakesItsIdFromItsState() {
        EntityState state = new EntityState(ACME, "Organization", Map.of(), EffectivePeriod.from(JANUARY), JANUARY,
                new Basis(1));
        TypedEntity entity = () -> state;

        assertThat(entity.entityState()).isSameAs(state);
        assertThat(entity.id()).isEqualTo(ACME);
    }

    @Test
    void aTypedRelationshipTakesItsIdAndEndsFromItsState() {
        RelationshipState state = new RelationshipState(OWNS, "Owns", ACME, BETA, Map.of(),
                EffectivePeriod.from(JANUARY), JANUARY, new Basis(1));
        TypedRelationship relationship = () -> state;

        assertThat(relationship.relationshipState()).isSameAs(state);
        assertThat(relationship.id()).isEqualTo(OWNS);
        assertThat(relationship.source()).isEqualTo(ACME);
        assertThat(relationship.target()).isEqualTo(BETA);
    }
}
