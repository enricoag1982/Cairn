package io.github.enricoag1982.cairn.example.hr;

import static io.github.enricoag1982.cairn.example.hr.Brightwater.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Submission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the rule {@code decided-requests-stay} does with the history of a request: it compares the version it checks with
 * every version that the store held before the transaction during the version's period, not only with the one at the
 * start of the period. The store is a store of its own with HR-1, HR-2 and the first requests of HR-5, found through
 * the service loader like an application's.
 */
class DecidedRequestsStayTest {

    private final TestClock clock = new TestClock();
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

    /** A store with the rules of the example, HR-1 and HR-2, and Dev's request of HR-5, still to be approved. */
    private void startWithDevsRequest() {
        store = Stores.inMemory(StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .discoverRules().build());
        clock.moveTo(on(1, 2));
        SchemaDocument document = Brightwater.schemaDocument();
        assertThat(store.schema().register(document.origin(), document.definition(),
                Submission.by(Brightwater.HR_ADMIN))).isInstanceOf(RegistrationResult.Applied.class);
        commit(Brightwater.hr1Structure(store.schema().current()));
        commit(Brightwater.hr2People(ids));
        commit(Brightwater.hr5DevRequestsVacation(ids));
    }

    /** A store with Dev's request approved on 2 March, as HR-5 tells, which the rule lets commit. */
    private void startWithDevsApprovedVacation() {
        startWithDevsRequest();
        commit(Brightwater.hr5CleoApproves(ids));
        assertThat(statusesOfTheRequest()).as("HR-5 approves the request without changing its dates")
                .containsExactly("requested", "approved");
    }

    private EntityId request() {
        return ids.entity("devVacation");
    }

    private List<LeaveRequest> versionsOfTheRequest() {
        return store.view(Coordinates.effectiveAt(on(12, 31))).timeline(request()).stream().map(LeaveRequest::from)
                .toList();
    }

    private List<String> statusesOfTheRequest() {
        return versionsOfTheRequest().stream().map(LeaveRequest::status).toList();
    }

    /** A transaction recorded on 9 March that patches Dev's request with the status and dates given, from a day. */
    private Transaction moved(String status, int fromMonth, int fromDay) {
        LeaveRequest.Patch patch = LeaveRequest.patch(request()).firstDay(LocalDate.of(2026, 3, 23))
                .lastDay(LocalDate.of(2026, 3, 27));
        if (status != null) {
            patch.status(status);
        }
        return Transaction.by(Brightwater.HR_ADMIN).write(patch.effectiveFrom(on(fromMonth, fromDay))).build();
    }

    private Problem rejected(Transaction transaction) {
        clock.moveTo(on(3, 9));
        CommitResult result = store.commit(transaction);
        assertThat(result).as("the transaction is rejected").isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) result).problems();
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private void assertTheRequestIsAsHr5LeftIt() {
        assertThat(versionsOfTheRequest()).extracting(LeaveRequest::status).containsExactly("requested", "approved");
        assertThat(versionsOfTheRequest()).extracting(LeaveRequest::firstDay)
                .containsOnly(LocalDate.of(2026, 3, 16));
        assertThat(versionsOfTheRequest()).extracting(LeaveRequest::lastDay).containsOnly(LocalDate.of(2026, 3, 20));
    }

    @Test
    void aChangeEffectiveBeforeTheApprovalCannotApproveTheRequestWithOtherDates() {
        startWithDevsApprovedVacation();

        Problem problem = rejected(moved("approved", 3, 1));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).as("from 2 March the request was approved with other dates")
                .contains("decided-requests-stay/decided");
        assertThat(problem.path()).isEqualTo(request().value());
        assertThat(problem.message()).contains("was approved before the transaction, from " + on(3, 2))
                .contains("the transaction changes firstDay, lastDay.");
        assertTheRequestIsAsHr5LeftIt();
    }

    @Test
    void aChangeOfTheDatesEffectiveBeforeTheApprovalIsRejectedAsWell() {
        startWithDevsApprovedVacation();

        Problem problem = rejected(moved(null, 3, 1));

        assertThat(problem.ruleCode()).contains("decided-requests-stay/decided");
        assertThat(problem.message()).contains("the transaction changes firstDay, lastDay.");
        assertTheRequestIsAsHr5LeftIt();
    }

    @Test
    void aMoveOfTheApprovedVacationOfHr16IsStillRejected() {
        startWithDevsApprovedVacation();
        Recording recording = Brightwater.hr16DevMovesApprovedVacation(ids);
        clock.moveTo(recording.recordedAt());

        CommitResult result = store.commit(recording.transaction());

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.ruleCode()).contains("decided-requests-stay/decided");
            assertThat(problem.message()).contains("the transaction changes firstDay, lastDay.");
        });
        assertTheRequestIsAsHr5LeftIt();
    }

    @Test
    void aRequestThatWasOnlyRequestedMayBeApprovedWithOtherDatesAndAnApprovedOneMayBeCancelled() {
        startWithDevsRequest();

        clock.moveTo(on(3, 2));
        assertThat(store.commit(moved("approved", 3, 2))).as("approved with other dates: it was only requested")
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(versionsOfTheRequest()).extracting(LeaveRequest::status).containsExactly("requested", "approved");
        assertThat(versionsOfTheRequest().getLast().firstDay()).isEqualTo(LocalDate.of(2026, 3, 23));

        clock.moveTo(on(3, 9));
        assertThat(store.commit(Transaction.by(Brightwater.HR_ADMIN)
                .write(LeaveRequest.patch(request()).status("cancelled").effectiveFrom(on(3, 9))).build()))
                .as("a decided request may be cancelled").isInstanceOf(CommitResult.Committed.class);
        assertThat(statusesOfTheRequest()).containsExactly("requested", "approved", "cancelled");
    }

    @Test
    void aDecidedRequestStaysInTheDaysItHad() {
        startWithDevsApprovedVacation();

        Problem problem = rejected(Transaction.by(Brightwater.HR_ADMIN).write(LeaveRequest.patch(request())
                .days(new BigDecimal("6")).effectiveFrom(on(3, 1))).build());

        assertThat(problem.ruleCode()).contains("decided-requests-stay/decided");
        assertThat(problem.message()).contains("the transaction changes days.");
        assertTheRequestIsAsHr5LeftIt();
    }
}
