package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A transaction with a clearance that has a problem {@code ACCESS_DENIED} ends there, in commits and dry runs alike
 * (ADR-0036, design D4 of {@code add-attribute-access-control}): its result holds those problems only, and nothing is
 * validated, derived, checked or run for it, so that no unique constraint, derivation, check or rule can be probed through
 * a value that was refused. The tests run against RocksDB stores too.
 */
class RefusedTransactionsTest {

    private static final TemporaryId NEW_ORGANIZATION = new TemporaryId("nameless");
    private static final String CENTS = "int(self.balance * 100.0)";

    private static SchemaDefinition finance() {
        return withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
    }

    private static List<Problem> rejectedDryRun(ReferenceStore reference, Transaction transaction) {
        DryRunResult dryRun = reference.store.dryRun(transaction);
        assertThat(dryRun).isInstanceOf(DryRunResult.Rejected.class);
        return ((DryRunResult.Rejected) dryRun).problems();
    }

    @Test
    void aRefusedTransactionHoldsOnlyTheProblemsOfTheRefusal() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        Transaction mixed = Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(a1, from(5, 1), values("balance", money("1500")))
                .create(NEW_ORGANIZATION, "Organization", from(5, 1), values("lei", "5493001KJTIIGC8Y1R12"))
                .create(new TemporaryId("odd"), "Organization", from(5, 1), values("name", "Odd", "bogus", "x"))
                .build();
        Transaction withoutTheRefusedWrite = Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .create(NEW_ORGANIZATION, "Organization", from(5, 1), values("lei", "5493001KJTIIGC8Y1R12"))
                .create(new TemporaryId("odd"), "Organization", from(5, 1), values("name", "Odd", "bogus", "x"))
                .build();

        List<Problem> dryRun = rejectedDryRun(reference, mixed);
        List<Problem> others = rejectedDryRun(reference, withoutTheRefusedWrite);

        assertThat(dryRun).extracting(Problem::code).containsExactly(ProblemCode.ACCESS_DENIED);
        assertThat(dryRun.getFirst().path()).isEqualTo(a1.value() + ".balance");
        assertThat(reference.store.commit(mixed)).isInstanceOfSatisfying(CommitResult.Rejected.class,
                rejected -> assertThat(rejected.problems()).isEqualTo(dryRun));
        assertThat(others).as("without the refused write, the other problems are reported").extracting(Problem::code)
                .contains(ProblemCode.MISSING_REQUIRED_VALUE, ProblemCode.UNKNOWN_ATTRIBUTE);
    }

    @Test
    void aRefusedTransactionRunsNoRule() {
        AccountFundedRule rule = new AccountFundedRule();
        ReferenceStore reference = ReferenceStore.withRules(withClassification(AccountFundedRule.model(), "Account",
                "balance", "finance"), ReferenceStore.rulesWith(rule), ReferenceModel.declaredRules());
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        reference.recordedOn(on(3, 2));
        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(3, 1), values("balance", money("900"))).build());
        reference.recordedOn(on(5, 2));
        EntityId acme = entity(reference.parties, ACME);
        EntityId alice = entity(reference.parties, ALICE);
        Transaction employs = Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .relate("Employs", acme, alice, from(5, 1), values("title", "Analyst")).build();
        Transaction refused = Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .relate("Employs", acme, alice, from(5, 1), values("title", "Analyst"))
                .patch(a1, from(5, 1), values("balance", money("5000"))).build();
        int checksBefore = rule.checks();

        List<Problem> forRefused = rejectedDryRun(reference, refused);
        assertThat(reference.store.commit(refused)).isInstanceOf(CommitResult.Rejected.class);
        int afterRefused = rule.checks();
        List<Problem> forEmploys = rejectedDryRun(reference, employs);

        assertThat(forRefused).extracting(Problem::code).containsExactly(ProblemCode.ACCESS_DENIED);
        assertThat(afterRefused).as("no rule ran for the refused transaction, run dry or committed")
                .isEqualTo(checksBefore);
        assertThat(forEmploys).as("without the refused write, the rule runs and rejects").extracting(Problem::code)
                .containsExactly(ProblemCode.RULE_VIOLATION);
        assertThat(rule.checks()).isGreaterThan(afterRefused);
    }

    @Test
    void aDerivationCannotBeProbedThroughARefusedValue() {
        SchemaDefinition definition = withClassification(Definitions.withDerivedAttribute(finance(), "Account",
                "balanceInCents", ValueType.LONG, false, CENTS), "Account", "balanceInCents", "finance");
        ReferenceStore reference = ReferenceStore.withExpressionLanguage(definition,
                ExpressionLanguages.discovered().orElseThrow());
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        Transaction huge = Transaction.by(PRINCIPAL).clearance(Clearance.none())
                .patch(a1, from(5, 1), values("balance", money("99999999999999999999999"))).build();
        Transaction hugeAndCleared = Transaction.by(PRINCIPAL).clearance(Clearance.of("finance"))
                .patch(a1, from(5, 1), values("balance", money("99999999999999999999999"))).build();

        List<Problem> probed = rejectedDryRun(reference, huge);
        List<Problem> cleared = rejectedDryRun(reference, hugeAndCleared);

        assertThat(probed).extracting(Problem::code).containsExactly(ProblemCode.ACCESS_DENIED);
        assertThat(cleared).as("a clearance that covers the balance gets the derivation's problem")
                .extracting(Problem::code).contains(ProblemCode.DERIVATION_FAILED);
    }
}
