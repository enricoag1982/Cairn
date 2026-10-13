package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final EffectivePeriod FROM_JAN_1 = EffectivePeriod.from(JAN_1);
    private static final EntityId ACME_ID = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final TemporaryId ACME = new TemporaryId("acme");

    @Test
    void aTransactionHasAPrincipalAndNothingElseByDefault() {
        Transaction transaction = Transaction.by("agent:onboarding").build();

        assertThat(transaction.principal()).isEqualTo("agent:onboarding");
        assertThat(transaction.onBehalfOf()).isEmpty();
        assertThat(transaction.reason()).isEmpty();
        assertThat(transaction.idempotencyKey()).isEmpty();
        assertThat(transaction.writes()).isEmpty();
    }

    @Test
    void recordsWhoForWhomAndWhy() {
        Transaction transaction = Transaction.by("agent:onboarding")
                .onBehalfOf("alice")
                .reason("New customer")
                .idempotencyKey("onboard-acme")
                .build();

        assertThat(transaction.principal()).isEqualTo("agent:onboarding");
        assertThat(transaction.onBehalfOf()).contains("alice");
        assertThat(transaction.reason()).contains("New customer");
        assertThat(transaction.idempotencyKey()).contains("onboard-acme");
    }

    @Test
    void aMissingPrincipalIsHeldAsAnEmptyOneSoThatTheStoreCanReportIt() {
        assertThat(Transaction.by(null).build().principal()).isEmpty();
        assertThat(Transaction.by("").build().principal()).isEmpty();
        assertThat(new Transaction(null, Optional.empty(), Optional.empty(), Optional.empty(), List.of()).principal())
                .isEmpty();
    }

    @Test
    void theBuilderRejectsNullParts() {
        Transaction.Builder builder = Transaction.by("p");

        assertThatThrownBy(() -> builder.onBehalfOf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.reason(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.idempotencyKey(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.write(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.create((TemporaryId) null, "Organization", FROM_JAN_1, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.create((EntityId) null, "Organization", FROM_JAN_1, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRecordRejectsNullParts() {
        assertThatThrownBy(() -> new Transaction("p", null, Optional.empty(), Optional.empty(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("p", Optional.empty(), null, Optional.empty(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("p", Optional.empty(), Optional.empty(), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transaction("p", Optional.empty(), Optional.empty(), Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createsInAllItsForms() {
        Map<String, Value> values = Map.of("name", Value.of("ACME"));

        Transaction transaction = Transaction.by("p")
                .create("Organization", FROM_JAN_1, values)
                .create(ACME, "Organization", FROM_JAN_1, values)
                .create(ACME_ID, "Organization", FROM_JAN_1, values)
                .create(ACME, ACME_ID, "Organization", FROM_JAN_1, values)
                .build();

        assertThat(transaction.writes()).containsExactly(
                new Write.Create(Optional.empty(), Optional.empty(), "Organization", FROM_JAN_1, values),
                new Write.Create(Optional.of(ACME), Optional.empty(), "Organization", FROM_JAN_1, values),
                new Write.Create(Optional.empty(), Optional.of(ACME_ID), "Organization", FROM_JAN_1, values),
                new Write.Create(Optional.of(ACME), Optional.of(ACME_ID), "Organization", FROM_JAN_1, values));
    }

    @Test
    void patchesReplacesAndEndDatesInAllTheirForms() {
        Map<String, Value> values = Map.of("name", Value.of("ACME Holdings"));
        Set<String> clear = Set.of("lei");
        ConflictCheck check = ConflictCheck.perAttribute(new Basis(3));

        Transaction transaction = Transaction.by("p")
                .patch(ACME, FROM_JAN_1, values)
                .patch(ACME_ID, FROM_JAN_1, values, check)
                .patch(ACME_ID, FROM_JAN_1, values, clear, check)
                .replace(ACME, FROM_JAN_1, values)
                .replace(ACME_ID, FROM_JAN_1, values, check)
                .endDate(ACME, JAN_1)
                .endDate(ACME_ID, JAN_1, check)
                .build();

        assertThat(transaction.writes()).containsExactly(
                new Write.Patch(ACME, FROM_JAN_1, values, Set.of(), ConflictCheck.none()),
                new Write.Patch(ACME_ID, FROM_JAN_1, values, Set.of(), check),
                new Write.Patch(ACME_ID, FROM_JAN_1, values, clear, check),
                new Write.Replace(ACME, FROM_JAN_1, values, ConflictCheck.none()),
                new Write.Replace(ACME_ID, FROM_JAN_1, values, check),
                new Write.EndDate(ACME, JAN_1, ConflictCheck.none()),
                new Write.EndDate(ACME_ID, JAN_1, check));
    }

    @Test
    void keepsTheWritesInTheOrderTheyWereAdded() {
        Transaction transaction = Transaction.by("p")
                .create(ACME, "Organization", FROM_JAN_1, Map.of("name", Value.of("ACME")))
                .patch(ACME, EffectivePeriod.from(Instant.parse("2026-03-01T00:00:00Z")),
                        Map.of("name", Value.of("ACME Holdings")))
                .endDate(ACME, Instant.parse("2027-01-01T00:00:00Z"))
                .build();

        assertThat(transaction.writes()).hasSize(3);
        assertThat(transaction.writes().get(0)).isInstanceOf(Write.Create.class);
        assertThat(transaction.writes().get(1)).isInstanceOf(Write.Patch.class);
        assertThat(transaction.writes().get(2)).isInstanceOf(Write.EndDate.class);
    }

    @Test
    void theBuiltTransactionDoesNotChangeWhenTheBuilderIsUsedAgain() {
        Transaction.Builder builder = Transaction.by("p").create("Organization", FROM_JAN_1, Map.of());
        Transaction first = builder.build();

        builder.create("Person", FROM_JAN_1, Map.of());

        assertThat(first.writes()).hasSize(1);
        assertThat(builder.build().writes()).hasSize(2);
    }

    @Test
    void theWritesAreCopiedAndImmutable() {
        List<Write> writes = new ArrayList<>(List.of(new Write.EndDate(ACME, JAN_1, ConflictCheck.none())));
        Transaction transaction = new Transaction("p", Optional.empty(), Optional.empty(), Optional.empty(), writes);

        writes.clear();

        assertThat(transaction.writes()).hasSize(1);
        assertThatThrownBy(() -> transaction.writes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void transactionsWithTheSameContentAreEqual() {
        Transaction first = Transaction.by("p").idempotencyKey("k")
                .create(ACME, "Organization", FROM_JAN_1, Map.of("name", Value.of("ACME"))).build();
        Transaction second = Transaction.by("p").idempotencyKey("k")
                .create(ACME, "Organization", FROM_JAN_1, Map.of("name", Value.of("ACME"))).build();
        Transaction other = Transaction.by("p").idempotencyKey("k")
                .create(ACME, "Organization", FROM_JAN_1, Map.of("name", Value.of("Beta"))).build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(other);
    }

    @Test
    void createRecordsCopyTheirValuesAndKeepTheirOrder() {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("name", Value.of("ACME"));
        values.put("lei", Value.of("5493001KJTIIGC8Y1R12"));
        values.put("a", Value.of(1));
        Write.Create create = new Write.Create(Optional.empty(), Optional.empty(), "Organization", FROM_JAN_1, values);

        values.put("late", Value.of(2));

        assertThat(create.values().keySet()).containsExactly("name", "lei", "a");
        assertThatThrownBy(() -> create.values().put("x", Value.of(3))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void patchRecordsCopyTheirCollectionsAndKeepTheirOrder() {
        Map<String, Value> set = new LinkedHashMap<>();
        set.put("z", Value.of(1));
        set.put("b", Value.of(2));
        Set<String> clear = new LinkedHashSet<>(List.of("y", "c"));
        Write.Patch patch = new Write.Patch(ACME, FROM_JAN_1, set, clear, ConflictCheck.none());

        set.clear();
        clear.clear();

        assertThat(patch.set().keySet()).containsExactly("z", "b");
        assertThat(patch.clear()).containsExactly("y", "c");
        assertThatThrownBy(() -> patch.clear().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void writeRecordsRejectNulls() {
        Map<String, Value> valuesWithNull = new LinkedHashMap<>();
        valuesWithNull.put("name", null);

        assertThatThrownBy(() -> new Write.Create(null, Optional.empty(), "T", FROM_JAN_1, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Create(Optional.empty(), null, "T", FROM_JAN_1, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Create(Optional.empty(), Optional.empty(), null, FROM_JAN_1, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Create(Optional.empty(), Optional.empty(), "T", null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Create(Optional.empty(), Optional.empty(), "T", FROM_JAN_1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Create(Optional.empty(), Optional.empty(), "T", FROM_JAN_1, valuesWithNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Patch(null, FROM_JAN_1, Map.of(), Set.of(), ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Patch(ACME, null, Map.of(), Set.of(), ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Patch(ACME, FROM_JAN_1, null, Set.of(), ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Patch(ACME, FROM_JAN_1, Map.of(), null, ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Patch(ACME, FROM_JAN_1, Map.of(), Set.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Replace(null, FROM_JAN_1, Map.of(), ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Replace(ACME, null, Map.of(), ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Replace(ACME, FROM_JAN_1, null, ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.Replace(ACME, FROM_JAN_1, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.EndDate(null, JAN_1, ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.EndDate(ACME, null, ConflictCheck.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Write.EndDate(ACME, JAN_1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anEndDateTruncatesItsInstantToMicroseconds() {
        Write.EndDate endDate = new Write.EndDate(ACME, Instant.parse("2026-06-01T00:00:00.123456789Z"),
                ConflictCheck.none());

        assertThat(endDate.from()).isEqualTo(Instant.parse("2026-06-01T00:00:00.123456Z"));
    }
}
