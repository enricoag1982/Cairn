package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultsAndHistoryTest {

    private static final Instant RECORDED = Instant.parse("2026-01-05T09:00:00Z");
    private static final EntityId ACME_ID = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final EntityId BETA_ID = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final Problem PROBLEM = new Problem(ProblemCode.MISSING_PRINCIPAL, "@transaction", "No principal.");

    private static Receipt receipt(Map<TemporaryId, Id> temporaryIds, List<Id> created) {
        return new Receipt(new Basis(1), RECORDED, temporaryIds, created, "agent:onboarding", Optional.of("alice"),
                Optional.of("New customer"), Optional.of("onboard-acme"));
    }

    @Test
    void aReceiptKeepsTheOrderOfTheTemporaryIdsAndOfTheCreatedEntities() {
        Map<TemporaryId, Id> temporaryIds = new LinkedHashMap<>();
        temporaryIds.put(new TemporaryId("beta"), BETA_ID);
        temporaryIds.put(new TemporaryId("acme"), ACME_ID);
        List<Id> created = new ArrayList<>(List.of(BETA_ID, ACME_ID));

        Receipt receipt = receipt(temporaryIds, created);
        temporaryIds.clear();
        created.clear();

        assertThat(receipt.temporaryIds().keySet()).containsExactly(new TemporaryId("beta"), new TemporaryId("acme"));
        assertThat(receipt.created()).containsExactly(BETA_ID, ACME_ID);
        assertThatThrownBy(() -> receipt.created().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> receipt.temporaryIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aReceiptHoldsWhatTheTransactionRecorded() {
        Receipt receipt = receipt(Map.of(), List.of(ACME_ID));

        assertThat(receipt.basis()).isEqualTo(new Basis(1));
        assertThat(receipt.transactionTime()).isEqualTo(RECORDED);
        assertThat(receipt.principal()).isEqualTo("agent:onboarding");
        assertThat(receipt.onBehalfOf()).contains("alice");
        assertThat(receipt.reason()).contains("New customer");
        assertThat(receipt.idempotencyKey()).contains("onboard-acme");
    }

    @Test
    void aReceiptRejectsNulls() {
        assertThatThrownBy(() -> new Receipt(null, RECORDED, Map.of(), List.of(), "p", Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), null, Map.of(), List.of(), "p", Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, null, List.of(), "p", Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, Map.of(), null, "p", Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, Map.of(), List.of(), null, Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, Map.of(), List.of(), "p", null,
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, Map.of(), List.of(), "p", Optional.empty(),
                null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Receipt(new Basis(1), RECORDED, Map.of(), List.of(), "p", Optional.empty(),
                Optional.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTransactionRecordHoldsWhoWhyAndWhen() {
        TransactionRecord record = new TransactionRecord(new Basis(2), RECORDED, "agent:onboarding",
                Optional.of("alice"), Optional.of("New customer"));

        assertThat(record.basis()).isEqualTo(new Basis(2));
        assertThat(record.transactionTime()).isEqualTo(RECORDED);
        assertThat(record.principal()).isEqualTo("agent:onboarding");
        assertThat(record.onBehalfOf()).contains("alice");
        assertThat(record.reason()).contains("New customer");
    }

    @Test
    void aTransactionRecordRejectsNulls() {
        assertThatThrownBy(() -> new TransactionRecord(null, RECORDED, "p", Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionRecord(new Basis(1), null, "p", Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionRecord(new Basis(1), RECORDED, null, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionRecord(new Basis(1), RECORDED, "p", null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionRecord(new Basis(1), RECORDED, "p", Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCommitResultIsCommittedWithAReceiptOrRejectedWithProblems() {
        Receipt receipt = receipt(Map.of(), List.of(ACME_ID));
        List<Problem> problems = new ArrayList<>(List.of(PROBLEM));

        CommitResult.Rejected rejected = new CommitResult.Rejected(problems);
        problems.clear();

        assertThat(new CommitResult.Committed(receipt).receipt()).isSameAs(receipt);
        assertThat(rejected.problems()).containsExactly(PROBLEM);
        assertThatThrownBy(() -> rejected.problems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new CommitResult.Committed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommitResult.Rejected(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDryRunResultWouldCommitOrIsRejectedWithProblems() {
        List<Problem> problems = new ArrayList<>(List.of(PROBLEM));

        DryRunResult.Rejected rejected = new DryRunResult.Rejected(problems);
        problems.clear();

        assertThat(new DryRunResult.WouldCommit()).isEqualTo(new DryRunResult.WouldCommit());
        assertThat(rejected.problems()).containsExactly(PROBLEM);
        assertThatThrownBy(() -> rejected.problems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new DryRunResult.Rejected(null)).isInstanceOf(NullPointerException.class);
    }
}
