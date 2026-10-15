package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The changes of the change feed and what they list (design D1 of {@code add-change-feed}). */
class ChangeRecordsTest {

    private static final Instant RECORDED = Instant.parse("2026-03-01T09:00:00.000001Z");
    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR_1 = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant JUN_1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant OCT_1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final EntityId ACME = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final EntityId BETA = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final RelationshipId OWNS = new RelationshipId("00000000-0000-7000-8000-000000000008");
    private static final TransactionRecord RECORD = new TransactionRecord(new Basis(4), RECORDED, "importer",
            Optional.empty(), Optional.empty());

    private static Changed changed(Id id, EffectivePeriod... periods) {
        return new Changed(id, "Organization", false, List.of(periods));
    }

    // ------------------------------------------------------------------ what is listed

    @Test
    void aChangedEntityHasItsIdTypeWhetherItWasCreatedAndItsPeriods() {
        Changed changed = new Changed(ACME, "Organization", true, List.of(EffectivePeriod.from(JAN_1)));

        assertThat(changed.id()).isEqualTo(ACME);
        assertThat(changed.type()).isEqualTo("Organization");
        assertThat(changed.created()).isTrue();
        assertThat(changed.periods()).containsExactly(EffectivePeriod.from(JAN_1));
        assertThat(new Changed(OWNS, "Owns", false, List.of(EffectivePeriod.from(JAN_1))).id())
                .as("a relationship too").isInstanceOf(RelationshipId.class);
    }

    @Test
    void periodsAreOrderedAndNeitherOverlapNorTouchAndOnlyTheLastCanBeOpen() {
        Changed several = changed(ACME, EffectivePeriod.between(JAN_1, MAR_1), EffectivePeriod.between(JUN_1, OCT_1),
                EffectivePeriod.from(Instant.parse("2027-01-01T00:00:00Z")));

        assertThat(several.periods()).hasSize(3);
        assertThatThrownBy(() -> changed(ACME, EffectivePeriod.between(JAN_1, MAR_1),
                EffectivePeriod.between(MAR_1, JUN_1))).as("touching").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changed(ACME, EffectivePeriod.between(JAN_1, JUN_1),
                EffectivePeriod.between(MAR_1, OCT_1))).as("overlapping").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changed(ACME, EffectivePeriod.between(JUN_1, OCT_1),
                EffectivePeriod.between(JAN_1, MAR_1))).as("out of order").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changed(ACME, EffectivePeriod.from(JAN_1), EffectivePeriod.between(JUN_1, OCT_1)))
                .as("an open period that is not the last").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changed(ACME, EffectivePeriod.between(MAR_1, JAN_1)))
                .as("a period that ends before it starts").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChangedEntityHasAtLeastOnePeriodAndANonBlankTypeAndNothingIsNull() {
        assertThatThrownBy(() -> new Changed(ACME, "Organization", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Changed(ACME, " ", true, List.of(EffectivePeriod.from(JAN_1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Changed(null, "Organization", true, List.of(EffectivePeriod.from(JAN_1))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Changed(ACME, null, true, List.of(EffectivePeriod.from(JAN_1))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Changed(ACME, "Organization", true, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void thePeriodsAreCopiedAndCannotBeChangedAfterwards() {
        List<EffectivePeriod> periods = new ArrayList<>(List.of(EffectivePeriod.from(JAN_1)));
        Changed changed = new Changed(ACME, "Organization", true, periods);

        periods.add(EffectivePeriod.from(OCT_1));

        assertThat(changed.periods()).hasSize(1);
        assertThatThrownBy(() -> changed.periods().add(EffectivePeriod.from(OCT_1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ the kinds of change

    @Test
    void aTransactionChangeHasItsRecordAndWhatItChangedOrderedByTheTextOfTheIds() {
        TransactionChange change = new TransactionChange(RECORD, List.of(
                changed(ACME, EffectivePeriod.from(JAN_1)), changed(BETA, EffectivePeriod.from(JAN_1)),
                changed(OWNS, EffectivePeriod.from(MAR_1))));

        assertThat(change.basis()).isEqualTo(new Basis(4));
        assertThat(change.transactionTime()).isEqualTo(RECORDED);
        assertThat(change.transaction()).isEqualTo(RECORD);
        assertThat(change.changed()).extracting(item -> item.id().value()).isSorted();
        assertThat(new TransactionChange(RECORD, List.of()).changed()).as("it can have changed nothing").isEmpty();
    }

    @Test
    void whatATransactionChangedMustBeOrderedByIdAsTextAndListEachIdOnce() {
        Changed acme = changed(ACME, EffectivePeriod.from(JAN_1));
        Changed beta = changed(BETA, EffectivePeriod.from(JAN_1));

        assertThatThrownBy(() -> new TransactionChange(RECORD, List.of(beta, acme)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionChange(RECORD, List.of(acme, acme)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionChange(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionChange(RECORD, null)).isInstanceOf(NullPointerException.class);
        EntityId shorter = new EntityId("b");
        EntityId longer = new EntityId("aa");
        assertThat(new TransactionChange(RECORD, List.of(changed(longer, EffectivePeriod.from(JAN_1)),
                changed(shorter, EffectivePeriod.from(JAN_1)))).changed()).as("as text, aa is before b")
                .extracting(item -> item.id().value()).containsExactly("aa", "b");
    }

    @Test
    void theListOfWhatATransactionChangedIsCopied() {
        List<Changed> changed = new ArrayList<>(List.of(changed(ACME, EffectivePeriod.from(JAN_1))));
        TransactionChange change = new TransactionChange(RECORD, changed);

        changed.add(changed(BETA, EffectivePeriod.from(JAN_1)));

        assertThat(change.changed()).hasSize(1);
        assertThatThrownBy(() -> change.changed().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSchemaChangeAndAPurgeChangeTakeTheirBasisAndTimeFromTheirRecords() {
        // The change holds the schema and never reads it, so a stand-in is enough here.
        Schema schema = (Schema) Proxy.newProxyInstance(Schema.class.getClassLoader(), new Class<?>[] {Schema.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        SchemaTransaction registration = new SchemaTransaction(new Basis(1), RECORDED, new Origin("reference-model",
                "1.0.0"), schema);
        PurgeRecord purge = new PurgeRecord(new Basis(9), RECORDED.plusSeconds(60), "dpo", Optional.empty(), "r", 1, 2);

        SchemaChange schemaChange = new SchemaChange(registration);
        PurgeChange purgeChange = new PurgeChange(purge);

        assertThat(schemaChange.basis()).isEqualTo(new Basis(1));
        assertThat(schemaChange.transactionTime()).isEqualTo(RECORDED);
        assertThat(schemaChange.registration()).isSameAs(registration);
        assertThat(purgeChange.basis()).isEqualTo(new Basis(9));
        assertThat(purgeChange.transactionTime()).isEqualTo(RECORDED.plusSeconds(60));
        assertThat(purgeChange.purge()).isEqualTo(purge);
        assertThatThrownBy(() -> new SchemaChange(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PurgeChange(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aChangeIsOneOfTheThreeKindsAndAMatchOnTheKindIsExhaustive() {
        List<Change> changes = List.of(new TransactionChange(RECORD, List.of()),
                new PurgeChange(new PurgeRecord(new Basis(5), RECORDED, "dpo", Optional.empty(), "r", 0, 0)));

        List<String> kinds = changes.stream().map(change -> switch (change) {
            case TransactionChange transaction -> "transaction";
            case SchemaChange schema -> "schema";
            case PurgeChange purge -> "purge";
        }).toList();

        assertThat(kinds).containsExactly("transaction", "purge");
        assertThat(Change.class.isSealed()).isTrue();
        assertThat(Change.class.getPermittedSubclasses()).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("TransactionChange", "SchemaChange", "PurgeChange");
    }
}
