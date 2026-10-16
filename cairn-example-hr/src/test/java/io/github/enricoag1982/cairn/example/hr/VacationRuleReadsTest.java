package io.github.enricoag1982.cairn.example.hr;

import static io.github.enricoag1982.cairn.example.hr.Brightwater.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.rules.DecidedByManager;
import io.github.enricoag1982.cairn.example.hr.rules.NoUnitCycles;
import io.github.enricoag1982.cairn.example.hr.rules.VacationWithinEntitlement;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Submission;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What the rule {@code vacation-within-entitlement} reads: its query starts from the requester's employment by its ID,
 * so a change to another employment does not check an approved vacation again, and a change to the requester's own
 * employment does (ADR-0025). The store is a store of its own with HR-1, HR-2 and HR-5, whose vacation rule counts its
 * checks.
 */
class VacationRuleReadsTest {

    /** The rule of the example, which counts how many versions it has been run for. */
    private static final class CountingRule implements EntityRule {

        private final EntityRule rule;
        private final AtomicInteger checks = new AtomicInteger();

        CountingRule(EntityRule rule) {
            this.rule = rule;
        }

        int checks() {
            return checks.get();
        }

        @Override
        public RuleDescriptor descriptor() {
            return rule.descriptor();
        }

        @Override
        public void check(EntityState version, RuleContext context) {
            checks.incrementAndGet();
            rule.check(version, context);
        }
    }

    private final TestClock clock = new TestClock();
    private final CountingRule vacation = new CountingRule(new VacationWithinEntitlement());
    private final Ids ids = new Ids();
    private Store store;

    private Receipt commit(Recording recording) {
        clock.moveTo(recording.recordedAt());
        CommitResult result = store.commit(recording.transaction());
        assertThat(result).as("the story is committed").isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        ids.record(receipt);
        return receipt;
    }

    /** A store with the rules of the example, HR-1 and HR-2, and Dev's approved vacation of HR-5. */
    private void startWithDevsApprovedVacation() {
        store = Stores.inMemory(StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .rules(new NoUnitCycles(), new DecidedByManager(), vacation).build());
        clock.moveTo(on(1, 2));
        SchemaDocument document = Brightwater.schemaDocument();
        assertThat(store.schema().register(document.origin(), document.definition(),
                Submission.by(Brightwater.HR_ADMIN))).isInstanceOf(RegistrationResult.Applied.class);
        commit(Brightwater.hr1Structure(store.schema().current()));
        commit(Brightwater.hr2People(ids));
        commit(Brightwater.hr5DevRequestsVacation(ids));
        commit(Brightwater.hr5CleoApproves(ids));
        assertThat(vacation.checks()).as("checked when the approved vacation was written").isPositive();
    }

    private Transaction payRise(String employment, String salary) {
        return Transaction.by(Brightwater.HR_ADMIN)
                .write(Employment.patch(ids.entity(employment)).annualSalary(new BigDecimal(salary))
                        .effectiveFrom(Brightwater.JANUARY_1))
                .build();
    }

    @Test
    void aChangeToAnotherEmploymentDoesNotCheckAnApprovedVacationAgain() {
        startWithDevsApprovedVacation();
        int checked = vacation.checks();

        CommitResult result = store.commit(payRise("e007", "61000"));

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(vacation.checks()).as("Gus's pay is not what Dev's vacation was checked against")
                .isEqualTo(checked);
    }

    @Test
    void aChangeToTheRequestersOwnEmploymentChecksTheApprovedVacationAgain() {
        startWithDevsApprovedVacation();
        int checked = vacation.checks();

        CommitResult result = store.commit(payRise("e004", "73000"));

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(vacation.checks()).as("the query started from Dev's employment, which changed")
                .isEqualTo(checked + 1);
    }

    @Test
    void theRuleStillRejectsAVacationThatGoesOverTheEntitlement() {
        startWithDevsApprovedVacation();
        Recording second = Brightwater.hr7DevSecondVacation(ids);
        clock.moveTo(second.recordedAt());

        CommitResult result = store.commit(second.transaction());

        assertThat(result).as("5 days in March and 21 in August make 26").isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem ->
                assertThat(problem.ruleCode()).contains("vacation-within-entitlement/over-entitlement"));
    }
}
