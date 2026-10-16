package io.github.enricoag1982.cairn.example.hr;

import static io.github.enricoag1982.cairn.example.hr.Brightwater.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.enricoag1982.cairn.agents.AgentPolicy;
import io.github.enricoag1982.cairn.agents.AgentTools;
import io.github.enricoag1982.cairn.agents.ApprovalQueue;
import io.github.enricoag1982.cairn.agents.ApprovalRequest;
import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.PurgeKey;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.Queries;
import io.github.enricoag1982.cairn.documents.QueryReadResult;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.example.hr.model.BelongsTo;
import io.github.enricoag1982.cairn.example.hr.model.DecidedBy;
import io.github.enricoag1982.cairn.example.hr.model.EmployedBy;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.model.EmploymentOf;
import io.github.enricoag1982.cairn.example.hr.model.Fills;
import io.github.enricoag1982.cairn.example.hr.model.HasLeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.Job;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.LegalEntity;
import io.github.enricoag1982.cairn.example.hr.model.Location;
import io.github.enricoag1982.cairn.example.hr.model.ManagedBy;
import io.github.enricoag1982.cairn.example.hr.model.OrgUnit;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.example.hr.model.Person;
import io.github.enricoag1982.cairn.example.hr.model.Position;
import io.github.enricoag1982.cairn.example.hr.model.WorksAt;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stories HR-1 to HR-17 of the HR domain ({@code docs/reference/hr-domain.md}), one test each: a replay of
 * Brightwater's year on one store, with a clock that moves to the recording date of each story. The store is created
 * with its rules found through the service loader, as an application's would be. The main code of the example uses
 * Cairn's public API only; the tests use it too, and also the {@code cairn-api} test fixture {@code TestClock}, a
 * clock that moves when told. HR-14 and HR-15 have stores of their own.
 *
 * <p>The stories replay in the order of their recording dates, not of their numbers, as the stories of the reference
 * domain do, so that the transaction time of each is its recording date: HR-1 and HR-2 (2 January), HR-5 to HR-8
 * (1 to 5 March), HR-16 (9 March), HR-3 (10 March), HR-9 and HR-10 (queries effective 31 March, read at this point of
 * the replay), HR-4 (15 April), HR-11 (20 May and 20 June), HR-13 (1 August), HR-12 (1 October) and HR-17
 * (1 November). HR-9 must read before the purge of HR-12, which takes Gus out of the headcount. A store's transaction times strictly increase, so
 * the second transaction of a day gets the next microsecond after the first; HR-1 and HR-2 are asserted by their day.
 *
 * <p>HR-15 (1 March) tells HR-5's request again, on a store of its own with HR-1 and HR-2, because a notifier must
 * subscribe before the request is committed, and the store that HR-5 filled holds it already. It runs right after HR-5,
 * at the place that its recording date gives it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HrStoriesTest {

    private static final Instant JANUARY_1 = on(1, 1);
    private static final Instant MARCH_31 = on(3, 31);

    private final TestClock clock = new TestClock();
    private final Ids ids = new Ids();
    private Store store;

    @BeforeAll
    void openStore() {
        store = Stores.inMemory(settings(clock));
    }

    /** The settings of every store of the tests: the clock, deterministic IDs, and the rules of the service loader. */
    private static StoreSettings settings(TestClock clock) {
        return StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic()).discoverRules().build();
    }

    private View view(Instant effectiveAt) {
        return store.view(Coordinates.effectiveAt(effectiveAt));
    }

    private Receipt commit(Recording recording) {
        clock.moveTo(recording.recordedAt());
        CommitResult result = store.commit(recording.transaction());
        assertThat(result).as("the story is committed").isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        ids.record(receipt);
        return receipt;
    }

    private List<Problem> reject(Recording recording) {
        clock.moveTo(recording.recordedAt());
        CommitResult result = store.commit(recording.transaction());
        assertThat(result).as("the story is rejected").isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    /** A story is recorded on its recording date: the clock stood there, so that is the transaction time. */
    private static void assertRecordedAt(Instant transactionTime, Instant recordingDate) {
        assertThat(transactionTime).as("recorded at").isEqualTo(recordingDate);
    }

    /** Two stories recorded on one day: the second gets the next microsecond, so only the day is the same. */
    private static void assertRecordedOn(Instant transactionTime, Instant recordingDay) {
        assertThat(transactionTime).as("recorded on").isAfterOrEqualTo(recordingDay)
                .isBefore(recordingDay.plus(1, ChronoUnit.DAYS));
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** What a state is called in a story: the code, number or name that its page uses. */
    private static String label(EntityState state) {
        return switch (state.entityType()) {
            case Person.TYPE -> Person.from(state).fullName();
            case LegalEntity.TYPE -> LegalEntity.from(state).name();
            case Location.TYPE -> Location.from(state).name();
            case Job.TYPE -> Job.from(state).code();
            case OrgUnit.TYPE -> OrgUnit.from(state).code();
            case Position.TYPE -> Position.from(state).code();
            case Employment.TYPE -> Employment.from(state).employeeNumber();
            default -> state.entityType() + " " + state.id();
        };
    }

    /** The labels of the entities at the other end of the relationships that a name leads to, ordered. */
    private static List<String> linked(View view, EntityId from, String name) {
        return view.navigate(from, name).stream().map(Link::entity).map(HrStoriesTest::label).sorted().toList();
    }

    private static List<String> labels(List<EntityState> states) {
        return states.stream().map(HrStoriesTest::label).sorted().toList();
    }

    private static QueryResult.Rows rows(QueryResult result) {
        assertThat(result).isInstanceOf(QueryResult.Rows.class);
        return (QueryResult.Rows) result;
    }

    /** The headcount query's answer as unit code to headcount, in the order of the rows. */
    private static Map<String, Long> headcounts(QueryResult result) {
        Map<String, Long> headcounts = new LinkedHashMap<>();
        for (var row : rows(result).rows()) {
            headcounts.put(row.string("unit").orElseThrow(), row.longValue("headcount").orElseThrow());
        }
        return headcounts;
    }

    private static BigDecimal salary(EntityState state) {
        return Employment.from(state).annualSalary();
    }

    private static final List<String> ENTITY_TYPES = List.of("Person", "LegalEntity", "Location", "Job", "OrgUnit",
            "Position", "Employment", "LeaveRequest");

    // ------------------------------------------------------------------ HR-1 and HR-2, recorded 2 January

    @Test
    @Order(1)
    @DisplayName("HR-1: the employers, locations, jobs, units and positions exist from 1 January")
    void hr1TheStructureExists() {
        clock.moveTo(on(1, 2));
        SchemaDocument document = Brightwater.schemaDocument();
        assertThat(document.origin()).isEqualTo(new Origin("hr-model", "1.0.0"));

        RegistrationResult registration = store.schema().register(document.origin(), document.definition(),
                Submission.by(Brightwater.HR_ADMIN));

        assertThat(registration).isInstanceOf(RegistrationResult.Applied.class);
        assertRecordedOn(((RegistrationResult.Applied) registration).transactionTime(), on(1, 2));
        assertThat(store.schema().availableRules()).extracting(RuleDescriptor::name).containsExactlyInAnyOrder(
                "decided-by-manager", "decided-requests-stay", "no-unit-cycles", "vacation-within-entitlement");
        assertThat(store.declaredRules()).as("every rule of the model has its code").isEmpty();

        Receipt structure = commit(Brightwater.hr1Structure(store.schema().current()));
        assertRecordedOn(structure.transactionTime(), on(1, 2));

        View january = view(JANUARY_1);
        assertThat(labels(january.entities("LegalEntity"))).containsExactly("Brightwater GmbH", "Brightwater S.r.l.");
        assertThat(january.entities("LegalEntity")).extracting(state -> LegalEntity.from(state).country())
                .containsExactlyInAnyOrder("DE", "IT");
        assertThat(labels(january.entities("Location"))).containsExactly("Berlin", "Milan");
        assertThat(labels(january.entities("Job"))).containsExactly("AE", "MGR", "SWE");
        assertThat(labels(january.entities("OrgUnit"))).containsExactly("BW", "ENG", "PLT", "SAL");
        assertThat(labels(january.entities("Position"))).containsExactly("P-100", "P-200", "P-210", "P-211", "P-212",
                "P-300", "P-301");
        assertThat(january.entities("Employment")).as("nobody is employed yet").isEmpty();

        assertThat(linked(january, ids.entity("bw"), PartOf.INVERSE)).containsExactly("ENG", "SAL");
        assertThat(linked(january, ids.entity("eng"), PartOf.INVERSE)).containsExactly("PLT");
        assertThat(linked(january, ids.entity("plt"), "PartOf")).containsExactly("ENG");
        assertThat(linked(january, ids.entity("bw"), "PartOf")).as("Brightwater is the root").isEmpty();
        assertThat(linked(january, ids.entity("plt"), BelongsTo.INVERSE)).containsExactly("P-210", "P-211", "P-212");
        assertThat(linked(january, ids.entity("sal"), BelongsTo.INVERSE)).containsExactly("P-300", "P-301");
        assertThat(linked(january, ids.entity("p210"), "OfJob")).containsExactly("MGR");
        assertThat(linked(january, ids.entity("p301"), "OfJob")).containsExactly("AE");

        assertThat(view(Instant.parse("2025-12-31T00:00:00Z")).entities("OrgUnit"))
                .as("nothing exists before 1 January").isEmpty();
    }

    /** One line of the table of people on the domain page. */
    private record Row(String employee, String person, String position, String unit, String location, String employer,
            String salary, String manages) {
    }

    @Test
    @Order(2)
    @DisplayName("HR-2: the seven people are hired and the four managers manage their units")
    void hr2ThePeopleAreHired() {
        Receipt people = commit(Brightwater.hr2People(ids));
        assertRecordedOn(people.transactionTime(), on(1, 2));

        View january = view(JANUARY_1);
        List<Row> rows = new ArrayList<>();
        for (EntityState employment : january.entities("Employment")) {
            EntityId id = employment.id();
            List<String> units = linked(january, id, ManagedBy.INVERSE);
            rows.add(new Row(label(employment), linked(january, id, "EmploymentOf").getFirst(),
                    linked(january, id, "Fills").getFirst(),
                    linked(january, january.navigate(id, "Fills").getFirst().entity().id(), "BelongsTo").getFirst(),
                    linked(january, id, "WorksAt").getFirst(), linked(january, id, "EmployedBy").getFirst(),
                    salary(employment).stripTrailingZeros().toPlainString(), String.join(",", units)));
        }
        assertThat(rows).containsExactlyInAnyOrder(
                new Row("E-001", "Ana Berg", "P-100", "BW", "Berlin", "Brightwater GmbH", "150000", "BW"),
                new Row("E-002", "Ben Ito", "P-200", "ENG", "Berlin", "Brightwater GmbH", "130000", "ENG"),
                new Row("E-003", "Cleo Rossi", "P-210", "PLT", "Milan", "Brightwater S.r.l.", "110000", "PLT"),
                new Row("E-004", "Dev Patel", "P-211", "PLT", "Milan", "Brightwater S.r.l.", "70000", ""),
                new Row("E-005", "Eli Novak", "P-212", "PLT", "Berlin", "Brightwater GmbH", "75000", ""),
                new Row("E-006", "Fay Moreau", "P-300", "SAL", "Berlin", "Brightwater GmbH", "120000", "SAL"),
                new Row("E-007", "Gus Lind", "P-301", "SAL", "Milan", "Brightwater S.r.l.", "60000", ""));
        assertThat(january.entities("Employment")).allSatisfy(state -> {
            Employment employment = Employment.from(state);
            assertThat(employment.kind()).isEqualTo("permanent");
            assertThat(employment.weeklyHours()).contains(40);
            assertThat(employment.currency()).isEqualTo("EUR");
        });

        // The full name of a person is derived (ADR-0031): the store composes it when it commits the person, and no
        // write sets it.
        assertThat(january.entities("Person")).extracting(state -> Person.from(state).fullName())
                .containsExactlyInAnyOrder("Ana Berg", "Ben Ito", "Cleo Rossi", "Dev Patel", "Eli Novak", "Fay Moreau",
                        "Gus Lind");
        EntityState dev = january.entities("Person").stream()
                .filter(state -> Person.from(state).givenName().equals("Dev")).findFirst().orElseThrow();
        assertThat(Person.from(dev).fullName()).isEqualTo("Dev Patel");
        assertThat(problemCodes(Transaction.by(Brightwater.HR_ADMIN)
                .patch(dev.id(), EffectivePeriod.from(JANUARY_1), Map.of("fullName", Value.of("Devi Patel"))).build()))
                .as("a write cannot set the full name").containsExactly(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);

        // The model's constraints and multiplicities, tried with dry runs that record nothing.
        assertThat(problemCodes(Transaction.by(Brightwater.HR_ADMIN)
                .write(newEmployment("E-008", "permanent").as("e008").effectiveFrom(JANUARY_1)).build()))
                .as("an employment needs a person, an employer, a location and a position")
                .containsExactly(ProblemCode.TOO_FEW_RELATIONSHIPS);
        assertThat(problemCodes(hire("E-001", "permanent", "p100"))).as("employee numbers are unique")
                .contains(ProblemCode.UNIQUE_VALUE_TAKEN);
        assertThat(problemCodes(hire("E-12", "permanent", "p100"))).as("an employee number is E- and three digits")
                .contains(ProblemCode.VALUE_PATTERN_MISMATCH);
        assertThat(problemCodes(hire("E-008", "temporary", "p100"))).as("an employment is permanent or fixed-term")
                .contains(ProblemCode.VALUE_NOT_ALLOWED);
        assertThat(problemCodes(hire("E-008", "permanent", "p301"))).as("a position has one employment at a time")
                .containsExactly(ProblemCode.TOO_MANY_RELATIONSHIPS);
        assertThat(problemCodes(Transaction.by(Brightwater.HR_ADMIN)
                .write(LegalEntity.create().name("Brightwater Ltd").country("Germany").effectiveFrom(JANUARY_1))
                .build())).as("a country is two capital letters").containsExactly(ProblemCode.VALUE_PATTERN_MISMATCH);
    }

    private static Employment.Create newEmployment(String number, String kind) {
        return Employment.create().employeeNumber(number).kind(kind).annualSalary(new BigDecimal("50000"))
                .currency("EUR");
    }

    /** The problems of a dry run that a store rejects. */
    private List<Problem> dryRunProblems(Transaction transaction) {
        DryRunResult result = store.dryRun(transaction);
        assertThat(result).as("the dry run is rejected").isInstanceOf(DryRunResult.Rejected.class);
        return ((DryRunResult.Rejected) result).problems();
    }

    /** A dry run's problem codes; empty if the transaction would be committed. */
    private Set<ProblemCode> problemCodes(Transaction transaction) {
        return switch (store.dryRun(transaction)) {
            case DryRunResult.WouldCommit ignored -> Set.of();
            case DryRunResult.Rejected rejected -> rejected.problems().stream().map(Problem::code)
                    .collect(Collectors.toSet());
        };
    }

    /** A new person with an employment that has all it needs, in Berlin at GmbH, and fills a position. */
    private Transaction hire(String number, String kind, String position) {
        TemporaryId employment = new TemporaryId("newEmployment");
        TemporaryId person = new TemporaryId("newcomer");
        return Transaction.by(Brightwater.HR_ADMIN)
                .write(Person.create().givenName("Hana").familyName("Kim").as("newcomer").effectiveFrom(JANUARY_1))
                .write(newEmployment(number, kind).as("newEmployment").effectiveFrom(JANUARY_1))
                .write(EmploymentOf.relate(employment, person).effectiveFrom(JANUARY_1))
                .write(EmployedBy.relate(employment, ids.entity("gmbh")).effectiveFrom(JANUARY_1))
                .write(WorksAt.relate(employment, ids.entity("berlin")).effectiveFrom(JANUARY_1))
                .write(Fills.relate(employment, ids.entity(position)).effectiveFrom(JANUARY_1))
                .build();
    }

    // ------------------------------------------------------------------ HR-5 to HR-8, recorded 1 to 5 March

    @Test
    @Order(3)
    @DisplayName("HR-5: Dev requests vacation and Cleo approves it")
    void hr5DevRequestsVacation() {
        assertRecordedAt(commit(Brightwater.hr5DevRequestsVacation(ids)).transactionTime(), on(3, 1));
        EntityId request = ids.entity("devVacation");

        EntityState requested = view(on(3, 1)).entity(request).orElseThrow();
        LeaveRequest asked = LeaveRequest.from(requested);
        assertThat(asked.kind()).isEqualTo("vacation");
        assertThat(asked.firstDay()).isEqualTo("2026-03-16");
        assertThat(asked.lastDay()).isEqualTo("2026-03-20");
        assertThat(asked.days()).isEqualByComparingTo("5");
        assertThat(asked.status()).isEqualTo("requested");
        assertThat(linked(view(on(3, 1)), request, HasLeaveRequest.INVERSE)).containsExactly("E-004");
        assertThat(view(on(2, 28)).entity(request)).as("not filed yet on 28 February").isEmpty();
        // The days of a request are derived (ADR-0035): the store counts the working days of its dates, and no write
        // sets them.
        assertThat(problemCodes(Transaction.by(Brightwater.HR_ADMIN)
                .patch(request, EffectivePeriod.from(on(3, 1)), Map.of("days", Value.of(new BigDecimal("6")))).build()))
                .as("a write cannot set the days").containsExactly(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);

        assertRecordedAt(commit(Brightwater.hr5CleoApproves(ids)).transactionTime(), on(3, 2));

        assertThat(LeaveRequest.from(view(on(3, 2)).entity(request).orElseThrow()).status()).isEqualTo("approved");
        assertThat(linked(view(on(3, 2)), request, "DecidedBy")).as("Cleo decides").containsExactly("E-003");
        assertThat(LeaveRequest.from(view(on(3, 1)).entity(request).orElseThrow()).status())
                .as("on 1 March it was still requested").isEqualTo("requested");
        assertThat(view(on(3, 2)).timeline(request)).extracting(state -> LeaveRequest.from(state).status())
                .containsExactly("requested", "approved");

        // The check leave-dates-in-order, written in the schema document: a request whose last day is before its first
        // day is refused.
        Transaction backwards = Transaction.by(Brightwater.HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 3, 23))
                        .lastDay(LocalDate.of(2026, 3, 20)).status("requested")
                        .as("backwards").effectiveFrom(on(3, 2)))
                .write(HasLeaveRequest.relate(ids.entity("e004"), new TemporaryId("backwards"))
                        .effectiveFrom(on(3, 2)))
                .build();
        Problem outOfOrder = only(dryRunProblems(backwards));
        assertThat(outOfOrder.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(outOfOrder.message()).contains("leave-dates-in-order").contains("The last day is before the first day.");
        assertThat(outOfOrder.path()).isEqualTo("$backwards");

        // The rule decided-by-manager: nobody decides their own request, even a manager of their own unit.
        Problem ownRequest = only(dryRunProblems(Brightwater.cleoVacationDecidedBy(ids, "e003").transaction()));
        assertThat(ownRequest.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(ownRequest.ruleCode()).contains("decided-by-manager/own-request");
        assertThat(ownRequest.path()).isEqualTo("$cleoVacationDecision");
        assertThat(store.dryRun(Brightwater.cleoVacationDecidedBy(ids, "e002").transaction()))
                .as("the same request decided by Ben, who manages Engineering above Platform")
                .isInstanceOf(DryRunResult.WouldCommit.class);

        // Cleo no longer manages Platform from 10 March, so her approval of the vacation from 16 March no longer meets
        // decided-by-manager. ManagedBy names no rule, but the rule read it when it checked her decision, so the
        // store checks the decision again when the transaction ends the ManagedBy, and rejects the transaction.
        String decision = ids.relationship("devVacationDecision").value();
        Problem noLongerManager = only(dryRunProblems(Transaction.by(Brightwater.HR_ADMIN)
                .endDate(ids.relationship("managedByPLT"), on(3, 10)).build()));
        assertThat(noLongerManager.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(noLongerManager.ruleCode()).contains("decided-by-manager/not-a-manager");
        assertThat(noLongerManager.path()).as("Cleo's decision, which the transaction does not name")
                .isEqualTo(decision);
        assertThat(noLongerManager.hint()).contains("The transaction changes what the rule read when it last "
                + "checked " + decision + ".");
    }

    // ------------------------------------------------------------------ HR-15, recorded 1 March, on a store of its own

    @Test
    @Order(4)
    @DisplayName("HR-15: a notifier that follows the change feed tells Cleo that Dev's vacation request waits for her")
    void hr15ANotifierTellsCleoThatARequestWaits() throws InterruptedException {
        // The story follows the first requests of HR-5 on a store of its own, which has HR-1 and HR-2 and a notifier that
        // subscribed at its latest basis: a story about a subscription cannot be told on the store that HR-5 filled.
        TestClock localClock = new TestClock();
        Store local = Stores.inMemory(settings(localClock));
        Ids localIds = new Ids();
        localClock.moveTo(on(1, 2));
        SchemaDocument document = Brightwater.schemaDocument();
        assertThat(local.schema().register(document.origin(), document.definition(),
                Submission.by(Brightwater.HR_ADMIN))).isInstanceOf(RegistrationResult.Applied.class);
        commitTo(local, localClock, localIds, Brightwater.hr1Structure(local.schema().current()));
        commitTo(local, localClock, localIds, Brightwater.hr2People(localIds));
        LeaveNotifier notifier = new LeaveNotifier(local);
        local.changeFeed(local.latestBasis()).subscribe(notifier);

        Receipt request = commitTo(local, localClock, localIds, Brightwater.hr5DevRequestsVacation(localIds));

        assertRecordedAt(request.transactionTime(), on(3, 1));
        LeaveNotifier.Notification notification = notifier.next();
        assertThat(notification.manager()).as("Cleo manages Dev's unit on the first day of the request")
                .isEqualTo("Cleo Rossi");
        assertThat(notification.requester()).isEqualTo("Dev Patel");
        assertThat(notification.request()).isEqualTo(localIds.entity("devVacation"));
        assertThat(notification.firstDay()).isEqualTo("2026-03-16");
        assertThat(notification.days()).isEqualByComparingTo("5");
        assertThat(notifier.threads()).as("the notifier ran on a thread of the subscription, not the committing one")
                .isNotEmpty().doesNotContain(Thread.currentThread());

        // Cleo's approval of the next day changes the request, and creates a decision; neither is a new request.
        Receipt approval = commitTo(local, localClock, localIds, Brightwater.hr5CleoApproves(localIds));
        notifier.awaitHandled(approval.basis());
        assertThat(notifier.pending()).as("nobody else is told, and Cleo only once").isZero();
        notifier.stop();
    }

    private Receipt commitTo(Store target, TestClock targetClock, Ids targetIds, Recording recording) {
        targetClock.moveTo(recording.recordedAt());
        CommitResult result = target.commit(recording.transaction());
        assertThat(result).as("the story is committed").isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        targetIds.record(receipt);
        return receipt;
    }

    @Test
    @Order(5)
    @DisplayName("HR-6: Gus cannot decide Eli's vacation request")
    void hr6GusCannotDecide() {
        Problem problem = only(reject(Brightwater.hr6EliRequestDecidedByGus(ids)));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("decided-by-manager/not-a-manager");
        assertThat(problem.path()).isEqualTo("$eliVacationDecision");
        assertThat(problem.message()).contains("E-007").contains("E-005").contains("PLT").contains("ENG")
                .contains("BW");
        assertThat(view(on(4, 20)).entities("LeaveRequest")).as("only Dev's request exists").hasSize(1);
    }

    @Test
    @Order(6)
    @DisplayName("HR-7: Dev's second vacation would take the year over 25 days")
    void hr7DevSecondVacationIsOverTheEntitlement() {
        Problem problem = only(reject(Brightwater.hr7DevSecondVacation(ids)));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("vacation-within-entitlement/over-entitlement");
        assertThat(problem.path()).isEqualTo("$devAugust.days");
        assertThat(problem.message()).contains("E-004").contains("26");
        assertThat(view(on(8, 3)).entities("LeaveRequest")).as("only the 5 days of HR-5").hasSize(1);

        // 5 and 20 days make exactly 25, the most the rule allows: 20 working days are 3 to 28 August.
        assertThat(store.dryRun(Brightwater.devAugustVacation(ids, LocalDate.of(2026, 8, 28)).transaction()))
                .isInstanceOf(DryRunResult.WouldCommit.class);
    }

    @Test
    @Order(7)
    @DisplayName("HR-8: Brightwater cannot become part of Platform")
    void hr8NoUnitCycles() {
        Problem problem = only(reject(Brightwater.hr8BrightwaterPartOfPlatform(ids)));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("no-unit-cycles/cycle");
        assertThat(problem.path()).isEqualTo("$bwPartOfPlt");
        assertThat(linked(view(on(3, 5)), ids.entity("bw"), "PartOf")).as("Brightwater is still the root").isEmpty();
    }

    // ------------------------------------------------------------------ HR-16, recorded 9 March

    @Test
    @Order(8)
    @DisplayName("HR-16: Dev cannot move his approved vacation")
    void hr16ADecidedRequestStays() {
        EntityId request = ids.entity("devVacation");

        Problem problem = only(reject(Brightwater.hr16DevMovesApprovedVacation(ids)));

        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("decided-requests-stay/decided");
        assertThat(problem.path()).as("Dev's request, which the transaction names").isEqualTo(request.value());
        assertThat(problem.message()).contains("was approved before the transaction")
                .contains("the transaction changes firstDay, lastDay.");
        LeaveRequest kept = LeaveRequest.from(view(on(3, 10)).entity(request).orElseThrow());
        assertThat(kept.firstDay()).as("the request is still 16 to 20 March").isEqualTo("2026-03-16");
        assertThat(kept.lastDay()).isEqualTo("2026-03-20");
        assertThat(kept.status()).isEqualTo("approved");

        // A decided request can be cancelled, as it is; nothing else of it changes, and no status but cancelled.
        Instant recorded = on(3, 9);
        assertThat(store.dryRun(Transaction.by(Brightwater.HR_ADMIN).write(LeaveRequest.patch(request)
                .status("cancelled").effectiveFrom(recorded)).build())).as("cancelled")
                .isInstanceOf(DryRunResult.WouldCommit.class);
        Problem reopened = only(dryRunProblems(Transaction.by(Brightwater.HR_ADMIN).write(LeaveRequest.patch(request)
                .status("requested").effectiveFrom(recorded)).build()));
        assertThat(reopened.ruleCode()).as("requested again").contains("decided-requests-stay/decided");
        assertThat(reopened.message()).contains("the transaction changes status.");
        // The days of a request are derived from its dates, so they change with them: one more working day.
        Problem longer = only(dryRunProblems(Transaction.by(Brightwater.HR_ADMIN).write(LeaveRequest.patch(request)
                .lastDay(LocalDate.of(2026, 3, 23)).effectiveFrom(recorded)).build()));
        assertThat(longer.ruleCode()).as("one more day").contains("decided-requests-stay/decided");
        assertThat(longer.message()).contains("the transaction changes lastDay, days.");
    }

    // ------------------------------------------------------------------ HR-3, recorded 10 March

    @Test
    @Order(9)
    @DisplayName("HR-3: Eli moves to Milan and to Brightwater S.r.l. from 1 July")
    void hr3EliMoves() {
        assertRecordedAt(commit(Brightwater.hr3EliMoves(ids)).transactionTime(), on(3, 10));

        EntityId eli = ids.entity("e005");
        View june30 = view(on(6, 30));
        View july1 = view(on(7, 1));
        assertThat(linked(june30, eli, "WorksAt")).containsExactly("Berlin");
        assertThat(linked(june30, eli, "EmployedBy")).containsExactly("Brightwater GmbH");
        assertThat(linked(july1, eli, "WorksAt")).containsExactly("Milan");
        assertThat(linked(july1, eli, "EmployedBy")).containsExactly("Brightwater S.r.l.");
        assertThat(linked(july1, ids.entity("milan"), WorksAt.INVERSE)).as("Milan staff on 1 July")
                .containsExactly("E-003", "E-004", "E-005", "E-007");
    }

    // ------------------------------------------------------------------ HR-9 and HR-10, queries effective 31 March

    @Test
    @Order(10)
    @DisplayName("HR-9: the headcount of each unit with the units below it, as a query and as JSON")
    void hr9Headcount() {
        assertThat(store.purges()).as("read before the purge of HR-12").isEmpty();
        View march31 = view(MARCH_31);

        QueryResult typed = march31.query(Reports.headcount());

        assertThat(headcounts(typed)).containsExactly(entry("BW", 7L), entry("ENG", 4L), entry("PLT", 3L),
                entry("SAL", 2L));

        QueryReadResult read = Queries.read(Reports.HEADCOUNT_JSON);
        assertThat(read).isInstanceOf(QueryReadResult.Read.class);
        QueryResult json = march31.query(((QueryReadResult.Read) read).query().query());
        assertThat(json).as("the JSON form of the query gives the same answer").isEqualTo(typed);
        assertThat(Queries.toJson(json)).contains("BW").contains("headcount");
    }

    @Test
    @Order(11)
    @DisplayName("HR-10: Dev's managers, from the nearest up, are Cleo, Ben and Ana")
    void hr10ManagersOfDev() {
        QueryResult.Rows managers = rows(view(MARCH_31).query(Reports.managersOf("E-004")));

        assertThat(managers.rows()).extracting(row -> row.entity("manager", Person::from).orElseThrow().givenName())
                .containsExactly("Cleo", "Ben", "Ana");
        assertThat(managers.rows()).extracting(row -> row.intValue("distance").orElseThrow())
                .containsExactly(0, 1, 2);
    }

    // ------------------------------------------------------------------ HR-4, recorded 15 April

    @Test
    @Order(12)
    @DisplayName("HR-4: a correction of Dev's salary reads differently as known on 1 April and now")
    void hr4DevSalaryCorrection() {
        assertRecordedAt(commit(Brightwater.hr4DevSalaryCorrection(ids)).transactionTime(), on(4, 15));

        EntityId dev = ids.entity("e004");
        Coordinates february1 = Coordinates.effectiveAt(on(2, 1));
        EntityState asKnownOnApril1 = store.view(february1.knownAt(on(4, 1))).entity(dev).orElseThrow();
        EntityState asKnownNow = store.view(february1).entity(dev).orElseThrow();

        assertThat(salary(asKnownOnApril1)).isEqualByComparingTo("70000");
        assertThat(salary(asKnownNow)).isEqualByComparingTo("72000");
    }

    // ------------------------------------------------------------------ HR-11, recorded 20 May and 20 June

    @Test
    @Order(13)
    @DisplayName("HR-11: Gus's sick leave, Gus leaves, and P-301 is the only vacant position")
    void hr11GusLeavesAndPositionIsVacant() {
        Receipt sickLeave = commit(Brightwater.hr11GusWasSick(ids));
        assertRecordedAt(sickLeave.transactionTime(), on(5, 20));

        EntityId sick = ids.entity("gusSick");
        LeaveRequest leave = LeaveRequest.from(view(on(5, 12)).entity(sick).orElseThrow());
        assertThat(leave.kind()).isEqualTo("sick");
        assertThat(leave.firstDay()).isEqualTo("2026-05-11");
        assertThat(leave.lastDay()).isEqualTo("2026-05-12");
        assertThat(leave.days()).isEqualByComparingTo("2");
        assertThat(leave.status()).isEqualTo("approved");
        assertThat(linked(view(on(5, 12)), sick, HasLeaveRequest.INVERSE)).containsExactly("E-007");
        assertThat(linked(view(on(5, 12)), sick, "DecidedBy")).as("Fay approves").containsExactly("E-006");

        Receipt leaving = commit(Brightwater.hr11GusLeaves(ids));
        assertRecordedAt(leaving.transactionTime(), on(6, 20));
        assertThat(leaving.ended()).as("the composition ends the sick leave with the employment")
                .contains(ids.entity("gusSick"));
        assertThat(leaving.ended()).as("and Fay's decision, which the transaction does not name, with the request")
                .containsSubsequence(ids.entity("gusSick"), ids.relationship("gusSickDecision"));

        List<String> vacantOnJuly1 = vacantPositions(on(7, 1));
        List<String> vacantOnJune30 = vacantPositions(on(6, 30));
        assertThat(vacantOnJuly1).containsExactly("P-301");
        assertThat(vacantOnJune30).isEmpty();
        assertThat(view(on(6, 30)).entity(ids.entity("e007"))).as("Gus works on 30 June").isPresent();
        assertThat(view(on(7, 1)).entity(ids.entity("e007"))).as("not on 1 July").isEmpty();
    }

    private List<String> vacantPositions(Instant effectiveAt) {
        return rows(view(effectiveAt).query(Reports.vacantPositions())).rows().stream()
                .map(row -> Position.from(row.entity("position").orElseThrow()).code()).toList();
    }

    // ------------------------------------------------------------------ HR-13, recorded 1 August

    @Test
    @Order(14)
    @DisplayName("HR-13: an agent hires Hana Kim, and the commit waits for a person's approval")
    void hr13AgentHiresHana() {
        clock.moveTo(on(8, 1));
        ApprovalQueue queue = new ApprovalQueue(store, clock);
        AgentPolicy policy = new AgentPolicy(AgentPolicy.Decision.ALLOW, AgentPolicy.Decision.REQUIRE_APPROVAL);
        AgentTools tools = new AgentTools(store, policy, queue, Brightwater.HR_ASSISTANT, Optional.empty());

        // The agent learns what the store holds and how to write to it.
        String description = tools.call("cairn_describe", "{}");
        assertThat(description).contains("\"principal\":\"agent:hr-assistant\"").contains("\"name\":\"hr-model\"")
                .contains("\"dataCommits\":\"requireApproval\"").contains("decided-by-manager/not-a-manager")
                .contains("vacation-within-entitlement/over-entitlement").contains("no-unit-cycles/cycle")
                .contains("leave-dates-in-order").contains("self.lastDay >= self.firstDay");
        assertThat(description).as("every rule has its code").doesNotContain("\"hasCode\":false");
        String payloadSchema = tools.call("cairn_json_schema", arguments("kind", "payload"));
        assertThat(payloadSchema).contains("\"writes\"").contains("LeaveRequest").contains("E-[0-9]{3}");
        String employmentValues = tools.call("cairn_json_schema", arguments("kind", "values", "type", "Employment"));
        assertThat(employmentValues).contains("employeeNumber").contains("permanent").contains("fixed-term");

        // It names the unit, job, location and employer by the codes and names it knows, in unique keys: it runs no
        // query to find their IDs, and the store resolves the keys when it commits.
        String payload = Brightwater.hr13HanaPayload();
        assertThat(payload).contains("\"key\": { \"code\": \"PLT\" }").contains("\"key\": { \"code\": \"SWE\" }")
                .contains("\"key\": { \"name\": \"Berlin\" }")
                .contains("\"key\": { \"name\": \"Brightwater GmbH\" }");
        assertThat(payload).as("the payload holds no ID").doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        assertThat(tools.call("cairn_dry_run", arguments("payload", payload))).contains("\"outcome\":\"wouldCommit\"");

        // The commit waits: nothing is written until a person decides.
        String pending = tools.call("cairn_commit", arguments("payload", payload));
        assertThat(pending).contains("\"outcome\":\"pending\"");
        assertThat(findHana(tools)).as("Hana does not exist yet").contains("\"rows\":[]");
        assertThat(queue.pending()).singleElement().satisfies(request -> {
            assertThat(request.kind()).isEqualTo(ApprovalRequest.Kind.DATA_COMMIT);
            assertThat(request.principal()).isEqualTo(Brightwater.HR_ASSISTANT);
        });
        String requestId = queue.pending().getFirst().id();
        assertThat(tools.call("cairn_approval_status", arguments("requestId", requestId)))
                .contains("\"status\":\"pending\"");

        // A person approves, and the transaction is committed as the agent's.
        ApprovalRequest approved = queue.approve(requestId, "hr-director");

        assertThat(approved.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(tools.call("cairn_approval_status", arguments("requestId", requestId)))
                .contains("\"status\":\"approved\"").contains("\"decidedBy\":\"hr-director\"");
        String hana = findHana(tools);
        assertThat(hana).contains("\"givenName\":\"Hana\"").contains("\"familyName\":\"Kim\"")
                .contains("\"fullName\":\"Hana Kim\"");
        View august1 = view(on(8, 1));
        EntityState person = august1.entities("Person").stream().filter(state -> Person.from(state).givenName()
                .equals("Hana")).findFirst().orElseThrow();
        EntityId employment = august1.navigate(person.id(), EmploymentOf.INVERSE).getFirst().entity().id();
        Employment hired = Employment.from(august1.entity(employment).orElseThrow());
        assertThat(hired.employeeNumber()).isEqualTo("E-008");
        assertThat(hired.kind()).isEqualTo("permanent");
        assertThat(hired.annualSalary()).isEqualByComparingTo("72000");
        assertThat(hired.currency()).isEqualTo("EUR");
        assertThat(linked(august1, employment, "WorksAt")).containsExactly("Berlin");
        assertThat(linked(august1, employment, "EmployedBy")).containsExactly("Brightwater GmbH");
        assertThat(linked(august1, employment, "Fills")).containsExactly("P-213");
        EntityId position = august1.navigate(employment, "Fills").getFirst().entity().id();
        assertThat(linked(august1, position, "BelongsTo")).containsExactly("PLT");
        assertThat(linked(august1, position, "OfJob")).containsExactly("SWE");
        assertThat(august1.entity(position).map(state -> Position.from(state).title())).contains("Software Engineer");
        assertThat(store.transactions().getLast().principal()).isEqualTo(Brightwater.HR_ASSISTANT);
        assertRecordedAt(store.transactions().getLast().transactionTime(), on(8, 1));

        // The agent reads the new employment by its number, which it knows, in a key, without looking for its ID.
        String byNumber = tools.call("cairn_read_entity", "{\"key\": {\"entityType\": \"Employment\", \"key\": "
                + "{\"employeeNumber\": \"E-008\"}}, \"effectiveAt\": \"2026-08-01T00:00:00Z\"}");
        assertThat(byNumber).contains("\"id\":\"" + employment.value() + "\"").contains("\"annualSalary\":72000");
        assertThat(tools.call("cairn_read_entity", "{\"key\": {\"entityType\": \"Employment\", \"key\": "
                + "{\"employeeNumber\": \"E-008\"}}, \"effectiveAt\": \"2026-07-31T00:00:00Z\"}"))
                .as("before Hana's first day, E-008 names no employment").contains("\"result\":null");
    }

    /** Hana and her position, as {@code cairn_query} answers. */
    private static String findHana(AgentTools tools) {
        String query = """
                { "from": { "type": "Person", "as": "person" },
                  "steps": [ { "where": { "compare": { "left": { "attribute": "givenName", "of": "person" },
                                                       "op": "eq", "right": { "value": "Hana" } } } } ] }
                """;
        return tools.call("cairn_query", arguments("query", query, "effectiveAt", "2026-08-01T00:00:00Z"));
    }

    /** The arguments of a tool call as a JSON object, from alternating names and text values. */
    private static String arguments(String... namesAndValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            json.append(i == 0 ? "" : ",").append(quote(namesAndValues[i])).append(':')
                    .append(quote(namesAndValues[i + 1]));
        }
        return json.append('}').toString();
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return quoted.append('"').toString();
    }

    // ------------------------------------------------------------------ HR-12, recorded 1 October

    @Test
    @Order(15)
    @DisplayName("HR-12: the data protection officer erases Gus's personal data")
    void hr12ErasureOfGus() {
        clock.moveTo(on(10, 1));
        EntityId gus = ids.entity("gus");
        EntityId employment = ids.entity("e007");
        EntityId sickLeave = ids.entity("gusSick");

        // Purging only the person is refused: the employment would be left without the EmploymentOf it requires.
        PurgeResult refused = store.purge(Brightwater.hr12ErasureOfGusPersonOnly(ids));
        assertThat(refused).isInstanceOf(PurgeResult.Rejected.class);
        Problem tooFew = only(((PurgeResult.Rejected) refused).problems());
        assertThat(tooFew.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
        assertThat(tooFew.path()).as("names his employment").isEqualTo(employment.value());
        assertThat(tooFew.message()).contains("EmploymentOf");
        assertThat(store.purges()).as("the refused purge recorded nothing").isEmpty();
        assertThat(store.wasPurged(gus)).isFalse();
        assertThat(view(MARCH_31).entity(gus)).as("Gus is still there").isPresent();

        PurgeResult result = store.purge(Brightwater.hr12ErasureOfGus(ids));

        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        PurgeResult.Purged purged = (PurgeResult.Purged) result;
        assertThat(purged.entities()).as("the person, the employment and, by the composition, the sick leave")
                .containsExactlyInAnyOrder(gus, employment, sickLeave);
        assertThat(purged.keys()).as("the employee number named his employment, which ended on 1 July")
                .containsExactly(new PurgeResult.ResolvedKey(new PurgeKey(Employment.byEmployeeNumber("E-007"),
                        Optional.empty()), employment));
        assertThat(purged.relationships()).as("EmploymentOf, EmployedBy, WorksAt, Fills, HasLeaveRequest, DecidedBy")
                .hasSize(6).contains(ids.relationship("gusSickDecision"), ids.relationship("gusHasSick"),
                        ids.relationship("employmentOf007"), ids.relationship("fills007"));
        assertRecordedAt(purged.record().transactionTime(), on(10, 1));
        assertThat(store.purges()).singleElement().satisfies(purge -> {
            assertThat(purge.principal()).isEqualTo("dpo");
            assertThat(purge.reason()).isEqualTo("DSR-2026-0042");
            assertThat(purge.entities()).isEqualTo(3);
            assertThat(purge.relationships()).isEqualTo(6);
        });
        assertThat(store.wasPurged(gus)).isTrue();
        assertThat(store.wasPurged(employment)).isTrue();
        assertThat(store.wasPurged(sickLeave)).isTrue();

        View march31 = view(MARCH_31);
        assertThat(march31.timeline(gus)).isEmpty();
        assertThat(march31.timeline(employment)).isEmpty();
        assertThat(march31.timeline(sickLeave)).isEmpty();
        assertThat(march31.relationshipTimeline(ids.relationship("gusSickDecision"))).isEmpty();
        assertThat(headcounts(march31.query(Reports.headcount()))).containsExactly(entry("BW", 6L), entry("ENG", 4L),
                entry("PLT", 3L), entry("SAL", 1L));
        EntityState p301 = march31.entity(ids.entity("p301")).orElseThrow();
        assertThat(Position.from(p301).code()).as("P-301 still exists").isEqualTo("P-301");
        assertThat(linked(march31, p301.id(), Fills.INVERSE)).isEmpty();
    }

    // ------------------------------------------------------------------ HR-17, recorded 1 November

    @Test
    @Order(16)
    @DisplayName("HR-17: Cleo leaves, and her decision of Dev's vacation stays")
    void hr17CleoLeavesAndHerDecisionStays() {
        EntityId request = ids.entity("devVacation");
        EntityId cleo = ids.entity("e003");
        EntityId platform = ids.entity("plt");

        Receipt leaving = commit(Brightwater.hr17CleoLeaves(ids));

        assertRecordedAt(leaving.transactionTime(), on(11, 1));
        assertThat(leaving.ended()).as("the transaction names every relationship of her employment").isEmpty();

        // Until 30 November she is employed, manages Platform, and the decision is as it was.
        View november30 = view(on(11, 30));
        assertThat(november30.entity(cleo).map(HrStoriesTest::label)).contains("E-003");
        assertThat(linked(november30, request, "DecidedBy")).containsExactly("E-003");
        assertThat(linked(november30, platform, ManagedBy.TYPE)).containsExactly("E-003");

        // From 1 December her employment does not exist, so a view effective then does not find it, nor what hangs on it.
        View december1 = view(on(12, 1));
        assertThat(december1.entity(cleo)).as("Cleo's employment has ended").isEmpty();
        assertThat(labels(december1.entities("Employment"))).doesNotContain("E-003");
        assertThat(linked(december1, platform, ManagedBy.TYPE)).as("Platform has no manager").isEmpty();
        assertThat(december1.navigate(cleo, "decisions")).as("an employment that has ended has no decisions to "
                + "navigate").isEmpty();
        assertThat(vacantPositions(on(12, 1))).containsExactly("P-210", "P-301");

        // Her decision of Dev's vacation stays: navigating from the request returns her employment as it last was,
        // whose period ends on 1 December, so the reader tells that it has ended.
        assertThat(december1.navigate(request, "DecidedBy")).singleElement().satisfies(link -> {
            assertThat(link.relationship().id()).isEqualTo(ids.relationship("devVacationDecision"));
            assertThat(link.relationship().period()).as("the decision itself is as it was")
                    .isEqualTo(EffectivePeriod.from(on(3, 2)));
            assertThat(link.entity().id()).isEqualTo(cleo);
            assertThat(Employment.from(link.entity()).employeeNumber()).isEqualTo("E-003");
            assertThat(link.entity().period()).isEqualTo(EffectivePeriod.between(JANUARY_1, on(12, 1)));
        });
        assertThat(view(on(3, 1)).navigate(request, "DecidedBy")).as("before the decision").isEmpty();

        // A query that follows the decision reads the same version.
        Query decided = Query.from(LeaveRequest.TYPE, "request").follow("request", DecidedBy.TYPE, "decider").build();
        assertThat(rows(december1.query(decided)).rows()).singleElement().satisfies(row -> {
            assertThat(row.entity("request", LeaveRequest::from).orElseThrow().kind()).isEqualTo("vacation");
            EntityState decider = row.entity("decider").orElseThrow();
            assertThat(Employment.from(decider).employeeNumber()).isEqualTo("E-003");
            assertThat(decider.period().until()).contains(on(12, 1));
        });

        // Dev's managers are now Ben and Ana, and nobody can be decided by Cleo from 1 December on.
        assertThat(rows(december1.query(Reports.managersOf("E-004"))).rows())
                .extracting(row -> row.entity("manager", Person::from).orElseThrow().givenName())
                .containsExactly("Ben", "Ana");
        Transaction afterwards = Transaction.by(Brightwater.HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 12, 14))
                        .lastDay(LocalDate.of(2026, 12, 18)).status("approved")
                        .as("devDecember").effectiveFrom(on(12, 2)))
                .write(HasLeaveRequest.relate(ids.entity("e004"), new TemporaryId("devDecember"))
                        .effectiveFrom(on(12, 2)))
                .write(DecidedBy.relate(new TemporaryId("devDecember"), cleo).effectiveFrom(on(12, 2)))
                .build();
        assertThat(problemCodes(afterwards)).as("Cleo does not exist on the day the decision would start")
                .contains(ProblemCode.END_NOT_EFFECTIVE);
    }

    // ------------------------------------------------------------------ HR-14, a company of its own

    @Test
    @Order(17)
    @DisplayName("HR-14: a generated company reads the same from a reopened durable store as from memory")
    void hr14GeneratedCompany(@TempDir Path directory) {
        CompanyGenerator.Company company = CompanyGenerator.generate(CompanyGenerator.SEED);
        assertThat(CompanyGenerator.generate(CompanyGenerator.SEED).monthEnds()).as("the same seed, the same company")
                .isEqualTo(company.monthEnds());
        assertThat(CompanyGenerator.generate(CompanyGenerator.SEED + 1).monthEnds()).as("another seed, another one")
                .isNotEqualTo(company.monthEnds());
        assertThat(company.events()).containsEntry("hires", CompanyGenerator.EMPLOYEES);
        assertThat(company.events()).as("a year of every kind of event").containsOnlyKeys("hires", "transfers",
                "payChanges", "leaves", "terminations").allSatisfy((kind, count) -> assertThat(count).isPositive());
        assertThat(company.monthEnds()).hasSize(12);

        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(settings(memoryClock));
        Ids memoryIds = play(memory, memoryClock, company);

        // The days of a leave are derived from its dates (ADR-0035): every generated leave has at least one working day.
        View endOfYear = memory.view(Coordinates.effectiveAt(on(12, 31)));
        int leaves = company.events().get("leaves");
        for (int leave = 1; leave <= leaves; leave++) {
            assertThat(endOfYear.timeline(memoryIds.entity("leave" + leave))).as("the versions of leave%d", leave)
                    .isNotEmpty().allSatisfy(state -> assertThat(LeaveRequest.from(state).days())
                            .isGreaterThanOrEqualTo(BigDecimal.ONE));
        }

        TestClock durableClock = new TestClock();
        try (DurableStore durable = Stores.durable(directory, settings(durableClock))) {
            play(durable, durableClock, company);
        }
        try (DurableStore reopened = Stores.durable(directory, settings(new TestClock()))) {
            assertThat(reopened.latestBasis()).isEqualTo(memory.latestBasis());
            assertThat(reopened.transactions()).isEqualTo(memory.transactions());
            assertThat(reopened.schema().current().origins()).isEqualTo(memory.schema().current().origins());

            List<EntityId> seen = new ArrayList<>();
            for (CompanyGenerator.MonthEnd end : company.monthEnds()) {
                Coordinates month = Coordinates.effectiveAt(end.effectiveAt());
                View inMemory = memory.view(month);
                View onDisk = reopened.view(month);
                String at = "at the end of " + end.effectiveAt();

                for (String type : ENTITY_TYPES) {
                    assertThat(onDisk.entities(type)).as("%s %s", type, at).isEqualTo(inMemory.entities(type));
                }
                QueryResult headcount = inMemory.query(Reports.headcount());
                assertThat(onDisk.query(Reports.headcount())).as("the headcount %s", at).isEqualTo(headcount);
                assertThat(onDisk.query(Reports.vacantPositions())).as("the vacant positions %s", at)
                        .isEqualTo(inMemory.query(Reports.vacantPositions()));

                // The generator simulated the company: the store must say the same, and the root counts everybody.
                Map<String, Long> expected = new LinkedHashMap<>();
                end.headcountByUnit().forEach((unit, count) -> expected.put(unit, count.longValue()));
                assertThat(headcounts(headcount)).as("the headcount %s", at)
                        .containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(inMemory.entities("Employment")).as("employments %s", at).hasSize(end.employments());
                assertThat(headcounts(headcount).get("BW")).as("headcount of BW %s", at)
                        .isEqualTo((long) end.employments());

                // The same month end as known then: the other axis of time.
                Coordinates knownThen = Coordinates.effectiveAt(on(12, 31)).knownAt(end.effectiveAt());
                for (String type : ENTITY_TYPES) {
                    assertThat(reopened.view(knownThen).entities(type)).as("%s as known %s", type, at)
                            .isEqualTo(memory.view(knownThen).entities(type));
                }
                inMemory.entities("Employment").forEach(state -> seen.add(state.id()));
                inMemory.entities("LeaveRequest").forEach(state -> seen.add(state.id()));
            }

            // Whole timelines, and the relationships that changed over them.
            View inMemory = memory.view(Coordinates.effectiveAt(on(12, 31)));
            View onDisk = reopened.view(Coordinates.effectiveAt(on(12, 31)));
            assertThat(seen).isNotEmpty();
            for (EntityId id : seen.stream().distinct().toList()) {
                assertThat(onDisk.timeline(id)).isEqualTo(inMemory.timeline(id));
                for (String name : List.of("Fills", "WorksAt", "EmployedBy", "EmploymentOf", "HasLeaveRequest",
                        "DecidedBy")) {
                    List<RelationshipState> history = inMemory.navigateTimeline(id, name);
                    assertThat(onDisk.navigateTimeline(id, name)).isEqualTo(history);
                }
            }
            assertThat(seen.stream().distinct().mapToInt(id -> memory.view(Coordinates.effectiveAt(on(12, 31)))
                    .navigateTimeline(id, "Fills").size()).max().orElse(0))
                    .as("some employee moved to another position").isGreaterThan(1);

            // A manager left, and the decisions that the manager took stay, readable from their requests in every store:
            // a decision is a relationship that outlives its target (ADR-0034).
            List<Link> decisions = new ArrayList<>();
            for (EntityState request : inMemory.entities("LeaveRequest")) {
                List<Link> links = inMemory.navigate(request.id(), "DecidedBy");
                assertThat(onDisk.navigate(request.id(), "DecidedBy")).as("the decision of %s", request.id())
                        .isEqualTo(links);
                decisions.addAll(links);
            }
            assertThat(decisions).as("decisions of a manager whose employment has ended").anySatisfy(link -> {
                assertThat(link.entity().period().until()).as("the employment of the decider has ended").isPresent();
                assertThat(inMemory.entity(link.entity().id())).as("and the view does not find it").isEmpty();
            });
        }
    }

    /** Registers the HR model in a store, commits the steps of a company, each at its recording date, and returns the IDs. */
    private static Ids play(Store store, TestClock clock, CompanyGenerator.Company company) {
        clock.moveTo(on(1, 2));
        SchemaDocument document = Brightwater.schemaDocument();
        assertThat(store.schema().register(document.origin(), document.definition(),
                Submission.by(CompanyGenerator.PRINCIPAL))).isInstanceOf(RegistrationResult.Applied.class);
        Ids ids = new Ids();
        for (CompanyGenerator.Step step : company.steps()) {
            clock.moveTo(step.recordedAt());
            Transaction transaction = step.transaction(ids);
            CommitResult result = store.commit(transaction);
            assertThat(result).as("%s: %s", step.recordedAt(), transaction.reason().orElse(""))
                    .isInstanceOf(CommitResult.Committed.class);
            ids.record(((CommitResult.Committed) result).receipt());
        }
        return ids;
    }
}
