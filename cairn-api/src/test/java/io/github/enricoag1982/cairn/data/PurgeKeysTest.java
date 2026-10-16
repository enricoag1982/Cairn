package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The keys of a purge and what a purge returns of them (ADR-0033, design D1 of {@code add-purges-by-unique-key}). */
class PurgeKeysTest {

    private static final Instant RECORDED = Instant.parse("2026-03-02T09:00:00Z");
    private static final Instant APRIL_1 = Instant.parse("2026-04-01T00:00:00Z");
    private static final EntityId ALICE = new EntityId("00000000-0000-7000-8000-000000000003");
    private static final EntityId ACME = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final UniqueKey ACME_LEI = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R12"));
    private static final UniqueKey A1 = new UniqueKey("Account", "number", Value.of("A-1"));
    private static final PurgeRecord RECORD = new PurgeRecord(new Basis(4), RECORDED, "dpo", Optional.empty(),
            "Erasure request", 1, 0);

    @Test
    void aPurgeKeyHoldsTheKeyAndTheEffectiveTimeIfThereIsOne() {
        PurgeKey open = new PurgeKey(ACME_LEI, Optional.empty());
        PurgeKey effective = new PurgeKey(ACME_LEI, Optional.of(APRIL_1));

        assertThat(open.key()).isEqualTo(ACME_LEI);
        assertThat(open.effectiveAt()).isEmpty();
        assertThat(effective.effectiveAt()).contains(APRIL_1);
        assertThat(open).isNotEqualTo(effective).isEqualTo(new PurgeKey(ACME_LEI, Optional.empty()));
    }

    @Test
    void aPurgeKeyRejectsNullComponents() {
        assertThatThrownBy(() -> new PurgeKey(null, Optional.empty())).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> new PurgeKey(ACME_LEI, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveAt");
    }

    @Test
    void theBuilderAddsKeysInTheOrderOfTheCallsAndKeepsThemApartFromTheIds() {
        Purge purge = Purge.by("dpo").reason("Erasure request").entity(A1).entity(ALICE).entity(ACME_LEI, APRIL_1)
                .entity(ACME).build();

        assertThat(purge.entities()).containsExactly(ALICE, ACME);
        assertThat(purge.keys()).containsExactly(new PurgeKey(A1, Optional.empty()),
                new PurgeKey(ACME_LEI, Optional.of(APRIL_1)));
    }

    @Test
    void aPurgeMayNameKeysOnly() {
        Purge purge = Purge.by("dpo").reason("Erasure request").entity(ACME_LEI).build();

        assertThat(purge.entities()).isEmpty();
        assertThat(purge.keys()).containsExactly(new PurgeKey(ACME_LEI, Optional.empty()));
    }

    @Test
    void aPurgeThatNamesNeitherAnEntityNorAKeyIsAProgrammingError() {
        assertThatThrownBy(() -> Purge.by("dpo").reason("Erasure request").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity")
                .hasMessageContaining("unique key");
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "Erasure request", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "Erasure request", List.of()))
                .as("the constructor of before has no keys").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConstructorOfBeforeNamesNoKeys() {
        Purge purge = new Purge("dpo", Optional.empty(), "Erasure request", List.of(ALICE));

        assertThat(purge.entities()).containsExactly(ALICE);
        assertThat(purge.keys()).isEmpty();
        assertThat(purge).isEqualTo(new Purge("dpo", Optional.empty(), "Erasure request", List.of(ALICE), List.of()));
    }

    @Test
    void aPurgeCopiesItsKeysAndTheBuilderIsNotAffectedByLaterCalls() {
        List<PurgeKey> keys = new ArrayList<>(List.of(new PurgeKey(ACME_LEI, Optional.empty())));
        Purge fromList = new Purge("dpo", Optional.empty(), "Erasure request", List.of(), keys);
        keys.add(new PurgeKey(A1, Optional.empty()));
        Purge.Builder builder = Purge.by("dpo").reason("Erasure request").entity(ACME_LEI);
        Purge first = builder.build();
        builder.entity(A1);

        assertThat(fromList.keys()).containsExactly(new PurgeKey(ACME_LEI, Optional.empty()));
        assertThat(first.keys()).containsExactly(new PurgeKey(ACME_LEI, Optional.empty()));
        assertThat(builder.build().keys()).hasSize(2);
        assertThatThrownBy(() -> first.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPurgeAndItsBuilderRejectNullKeys() {
        assertThatThrownBy(() -> Purge.by("dpo").entity((UniqueKey) null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> Purge.by("dpo").entity(null, APRIL_1)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> Purge.by("dpo").entity(ACME_LEI, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveAt");
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "Erasure request", List.of(ALICE), null))
                .isInstanceOf(NullPointerException.class);
        List<PurgeKey> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new Purge("dpo", Optional.empty(), "Erasure request", List.of(), withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aResolvedKeyHoldsTheKeyAndTheEntityItNamed() {
        PurgeKey key = new PurgeKey(ACME_LEI, Optional.empty());

        PurgeResult.ResolvedKey resolved = new PurgeResult.ResolvedKey(key, ACME);

        assertThat(resolved.key()).isSameAs(key);
        assertThat(resolved.entity()).isEqualTo(ACME);
        assertThatThrownBy(() -> new PurgeResult.ResolvedKey(null, ACME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeResult.ResolvedKey(key, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPurgedResultListsTheEntityOfEachKeyAndCopiesTheList() {
        List<PurgeResult.ResolvedKey> keys = new ArrayList<>(
                List.of(new PurgeResult.ResolvedKey(new PurgeKey(ACME_LEI, Optional.empty()), ACME)));

        PurgeResult.Purged purged = new PurgeResult.Purged(RECORD, List.of(ACME), List.of(), keys);
        keys.clear();

        assertThat(purged.keys()).containsExactly(
                new PurgeResult.ResolvedKey(new PurgeKey(ACME_LEI, Optional.empty()), ACME));
        assertThatThrownBy(() -> purged.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PurgeResult.Purged(RECORD, List.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theConstructorOfBeforeGivesAResultWithoutKeys() {
        PurgeResult.Purged purged = new PurgeResult.Purged(RECORD, List.of(ALICE), List.of());

        assertThat(purged.keys()).isEmpty();
        assertThat(purged).isEqualTo(new PurgeResult.Purged(RECORD, List.of(ALICE), List.of(), List.of()));
    }
}
