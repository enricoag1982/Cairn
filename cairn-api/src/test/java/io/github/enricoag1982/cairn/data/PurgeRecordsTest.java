package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The purge, its result and its record (design D1 of {@code add-purge}). */
class PurgeRecordsTest {

    private static final Instant RECORDED = Instant.parse("2026-03-02T09:00:00Z");
    private static final EntityId ALICE = new EntityId("00000000-0000-7000-8000-000000000003");
    private static final EntityId BETA = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final RelationshipId EMPLOYS = new RelationshipId("00000000-0000-7000-8000-000000000009");

    @Test
    void aPurgeHoldsWhoAsksForWhomWhyAndWhat() {
        Purge purge = Purge.by("dpo").onBehalfOf("alice").reason("Erasure request").entity(ALICE).entity(BETA).build();

        assertThat(purge.principal()).isEqualTo("dpo");
        assertThat(purge.onBehalfOf()).contains("alice");
        assertThat(purge.reason()).isEqualTo("Erasure request");
        assertThat(purge.entities()).containsExactly(ALICE, BETA);
    }

    @Test
    void thePartyActedForIsOptional() {
        Purge purge = Purge.by("dpo").reason("Erasure request").entity(ALICE).build();

        assertThat(purge.onBehalfOf()).isEmpty();
    }

    @Test
    void aBlankOrMissingReasonIsAProgrammingError() {
        assertThatThrownBy(() -> Purge.by("dpo").reason(" ").entity(ALICE).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> Purge.by("dpo").reason("").entity(ALICE).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Purge.by("dpo").entity(ALICE).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "\t", List.of(ALICE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPurgeWithoutEntitiesIsAProgrammingError() {
        assertThatThrownBy(() -> Purge.by("dpo").reason("Erasure request").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity");
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "Erasure request", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankPrincipalIsAProgrammingError() {
        assertThatThrownBy(() -> Purge.by(" ").reason("Erasure request").entity(ALICE).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("principal");
        assertThatThrownBy(() -> Purge.by(null).reason("Erasure request").entity(ALICE).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPurgeCopiesItsEntitiesAndTheBuilderIsNotAffectedByLaterCalls() {
        List<EntityId> entities = new ArrayList<>(List.of(ALICE));
        Purge fromList = new Purge("dpo", Optional.empty(), "Erasure request", entities);
        entities.add(BETA);
        Purge.Builder builder = Purge.by("dpo").reason("Erasure request").entity(ALICE);
        Purge first = builder.build();
        builder.entity(BETA);

        assertThat(fromList.entities()).containsExactly(ALICE);
        assertThat(first.entities()).containsExactly(ALICE);
        assertThat(builder.build().entities()).containsExactly(ALICE, BETA);
        assertThatThrownBy(() -> first.entities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPurgeRejectsNullComponents() {
        assertThatThrownBy(() -> new Purge("dpo", null, "Erasure request", List.of(ALICE)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), null, List.of(ALICE)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Purge.by("dpo").entity(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Purge.by("dpo").onBehalfOf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Purge.by("dpo").reason(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPurgeRecordHoldsWhatWasRemovedInTheOrderGivenAndCopiesIt() {
        List<EntityId> entities = new ArrayList<>(List.of(ALICE, BETA));
        List<RelationshipId> relationships = new ArrayList<>(List.of(EMPLOYS));

        PurgeRecord record = new PurgeRecord(new Basis(4), RECORDED, "dpo", Optional.of("alice"), "Erasure request",
                entities, relationships);
        entities.clear();
        relationships.clear();

        assertThat(record.basis()).isEqualTo(new Basis(4));
        assertThat(record.transactionTime()).isEqualTo(RECORDED);
        assertThat(record.principal()).isEqualTo("dpo");
        assertThat(record.onBehalfOf()).contains("alice");
        assertThat(record.reason()).isEqualTo("Erasure request");
        assertThat(record.entities()).containsExactly(ALICE, BETA);
        assertThat(record.relationships()).containsExactly(EMPLOYS);
        assertThatThrownBy(() -> record.entities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> record.relationships().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPurgeRecordRejectsNullComponents() {
        assertThatThrownBy(() -> new PurgeRecord(null, RECORDED, "dpo", Optional.empty(), "why", List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeRecord(new Basis(1), null, "dpo", Optional.empty(), "why", List.of(),
                List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeRecord(new Basis(1), RECORDED, null, Optional.empty(), "why", List.of(),
                List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeRecord(new Basis(1), RECORDED, "dpo", null, "why", List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeRecord(new Basis(1), RECORDED, "dpo", Optional.empty(), null, List.of(),
                List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPurgeResultIsPurgedWithItsRecordOrRejectedWithACopyOfTheProblems() {
        PurgeRecord record = new PurgeRecord(new Basis(4), RECORDED, "dpo", Optional.empty(), "Erasure request",
                List.of(ALICE), List.of(EMPLOYS));
        List<Problem> problems = new ArrayList<>(List.of(new Problem(ProblemCode.UNKNOWN_ENTITY, "@purge.0", "No.")));

        PurgeResult purged = new PurgeResult.Purged(record);
        PurgeResult.Rejected rejected = new PurgeResult.Rejected(problems);
        problems.clear();

        assertThat(((PurgeResult.Purged) purged).record()).isSameAs(record);
        assertThat(rejected.problems()).hasSize(1);
        assertThatThrownBy(() -> rejected.problems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PurgeResult.Purged(null)).isInstanceOf(NullPointerException.class);
    }
}
