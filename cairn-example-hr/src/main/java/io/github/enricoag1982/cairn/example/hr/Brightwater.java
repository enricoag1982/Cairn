package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.PayloadReadResult;
import io.github.enricoag1982.cairn.documents.Payloads;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.example.hr.model.DecidedBy;
import io.github.enricoag1982.cairn.example.hr.model.EmployedBy;
import io.github.enricoag1982.cairn.example.hr.model.Employment;
import io.github.enricoag1982.cairn.example.hr.model.EmploymentOf;
import io.github.enricoag1982.cairn.example.hr.model.Fills;
import io.github.enricoag1982.cairn.example.hr.model.HasLeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.LeaveRequest;
import io.github.enricoag1982.cairn.example.hr.model.ManagedBy;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.example.hr.model.Person;
import io.github.enricoag1982.cairn.example.hr.model.WorksAt;
import io.github.enricoag1982.cairn.schema.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Brightwater's data: the schema document, and the transactions of the stories of the HR domain
 * ({@code docs/reference/hr-domain.md}) as the application records them.
 *
 * <p>HR-1 is a transaction payload, a YAML text that {@link Payloads} reads into a transaction; HR-2 is built with the
 * generated builders. A story that changes what an earlier one created refers to it by name through {@link Ids}.
 *
 * <p>Stories are in 2026. Every effective date is midnight UTC. A leave request is effective from the day it is
 * filed, or from its first day if that is earlier, as for sick leave that is recorded afterwards; a decision is
 * effective from the day it is recorded, or from the request's first day if that is earlier.
 */
public final class Brightwater {

    /** The principal of the HR administrator, who records the stories that no other principal records. */
    public static final String HR_ADMIN = "hr-admin";

    /** The principal that erases personal data in the story HR-12. */
    public static final String DPO = "dpo";

    /** The reason of the erasure in HR-12: a ticket, never the data subject's name. */
    public static final String ERASURE_TICKET = "DSR-2026-0042";

    /** The principal of the agent that hires Hana Kim in the story HR-13. */
    public static final String HR_ASSISTANT = "agent:hr-assistant";

    /** Where the company's year begins: 1 January. */
    public static final Instant JANUARY_1 = on(1, 1);

    private static final String SCHEMA_DOCUMENT = "/hr-model.cairn.yaml";
    private static final String STRUCTURE_PAYLOAD = "structure.payload.yaml";

    private Brightwater() {
    }

    // ------------------------------------------------------------------ the schema document

    /**
     * The text of the schema document {@code hr-model.cairn.yaml}, as the module holds it.
     *
     * @return the text, YAML 1.2
     */
    public static String schemaDocumentText() {
        return resource(Brightwater.class.getResourceAsStream(SCHEMA_DOCUMENT), SCHEMA_DOCUMENT);
    }

    /**
     * Reads the schema document: the origin {@code hr-model} version {@code 1.0.0} and its definition.
     *
     * @return the document, ready to register
     * @throws IllegalStateException if the document has problems
     */
    public static SchemaDocument schemaDocument() {
        return switch (SchemaDocuments.read(schemaDocumentText())) {
            case DocumentReadResult.Read read -> read.document();
            case DocumentReadResult.Invalid invalid -> throw new IllegalStateException(
                    "The schema document of Brightwater has problems: " + invalid.problems());
        };
    }

    // ------------------------------------------------------------------ HR-1 and HR-2: the company

    /**
     * HR-1. Recorded 2 January: the employers, locations, jobs, units with their hierarchy and positions exist from
     * 1 January. The transaction is a payload, read with {@link Payloads#readTransaction(String, Schema)}; its
     * temporary IDs are {@code gmbh}, {@code srl}, {@code berlin}, {@code milan}, {@code mgr}, {@code swe}, {@code ae},
     * {@code bw}, {@code eng}, {@code sal}, {@code plt} and the positions {@code p100} to {@code p301}.
     *
     * @param schema the schema of the store, with the HR model registered
     * @return the recording
     * @throws IllegalStateException if the payload has problems
     */
    public static Recording hr1Structure(Schema schema) {
        String payload = resource(Brightwater.class.getResourceAsStream(STRUCTURE_PAYLOAD), STRUCTURE_PAYLOAD);
        return switch (Payloads.readTransaction(payload, schema)) {
            case PayloadReadResult.Read read -> new Recording(on(1, 2), read.payload().transaction());
            case PayloadReadResult.Invalid invalid -> throw new IllegalStateException(
                    "The payload " + STRUCTURE_PAYLOAD + " has problems: " + invalid.problems());
        };
    }

    /** One of the seven people of the table of the domain page. */
    record Employee(String key, String givenName, String familyName, String position, String unitCode,
            String location, String employer, String salary, boolean manager) {

        String number() {
            return "E-" + key.substring(1);
        }
    }

    /** The seven people of the table of the domain page; a test keeps them in step with it. */
    static final List<Employee> EMPLOYEES = List.of(
            new Employee("e001", "Ana", "Berg", "p100", "bw", "berlin", "gmbh", "150000", true),
            new Employee("e002", "Ben", "Ito", "p200", "eng", "berlin", "gmbh", "130000", true),
            new Employee("e003", "Cleo", "Rossi", "p210", "plt", "milan", "srl", "110000", true),
            new Employee("e004", "Dev", "Patel", "p211", "plt", "milan", "srl", "70000", false),
            new Employee("e005", "Eli", "Novak", "p212", "plt", "berlin", "gmbh", "75000", false),
            new Employee("e006", "Fay", "Moreau", "p300", "sal", "berlin", "gmbh", "120000", true),
            new Employee("e007", "Gus", "Lind", "p301", "sal", "milan", "srl", "60000", false));

    /**
     * HR-2. Recorded 2 January: the seven people of the table are hired, and the four managers manage their units, all
     * from 1 January. The transaction is built with the generated builders. A person is named by the first name in
     * lower case, such as {@code gus}, an employment by its number, such as {@code e007}, and the relationships of an
     * employment {@code worksAt007}, {@code employedBy007}, {@code fills007} and {@code employmentOf007}.
     *
     * @param ids the IDs of HR-1
     * @return the recording
     */
    public static Recording hr2People(Ids ids) {
        Transaction.Builder transaction = Transaction.by(HR_ADMIN).reason("The first employees of Brightwater");
        for (Employee employee : EMPLOYEES) {
            String name = employee.givenName().toLowerCase(Locale.ROOT);
            String suffix = employee.key().substring(1);
            Ref person = new TemporaryId(name);
            Ref employment = new TemporaryId(employee.key());
            transaction
                    .write(Person.create().givenName(employee.givenName()).familyName(employee.familyName())
                            .as(name).effectiveFrom(JANUARY_1))
                    .write(Employment.create().employeeNumber(employee.number()).kind("permanent").weeklyHours(40)
                            .annualSalary(new BigDecimal(employee.salary())).currency("EUR").as(employee.key())
                            .effectiveFrom(JANUARY_1))
                    .write(EmploymentOf.relate(employment, person).as("employmentOf" + suffix)
                            .effectiveFrom(JANUARY_1))
                    .write(EmployedBy.relate(employment, ids.entity(employee.employer())).as("employedBy" + suffix)
                            .effectiveFrom(JANUARY_1))
                    .write(WorksAt.relate(employment, ids.entity(employee.location())).as("worksAt" + suffix)
                            .effectiveFrom(JANUARY_1))
                    .write(Fills.relate(employment, ids.entity(employee.position())).as("fills" + suffix)
                            .effectiveFrom(JANUARY_1));
            if (employee.manager()) {
                transaction.write(ManagedBy.relate(ids.entity(employee.unitCode()), employment)
                        .as("managedBy" + employee.unitCode().toUpperCase(Locale.ROOT))
                        .effectiveFrom(JANUARY_1));
            }
        }
        return new Recording(on(1, 2), transaction.build());
    }

    // ------------------------------------------------------------------ HR-3 and HR-4: changes in time

    /**
     * HR-3. Recorded 10 March: Eli moves to Milan and to Brightwater S.r.l. from 1 July. The old {@code WorksAt} and
     * {@code EmployedBy} end on 1 July and new ones start on it.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr3EliMoves(Ids ids) {
        Instant july1 = on(7, 1);
        EntityId eli = ids.entity("e005");
        return new Recording(on(3, 10), Transaction.by(HR_ADMIN).reason("Eli moves to Milan")
                .endDate(ids.relationship("worksAt005"), july1)
                .write(WorksAt.relate(eli, ids.entity("milan")).as("worksAt005Milan").effectiveFrom(july1))
                .endDate(ids.relationship("employedBy005"), july1)
                .write(EmployedBy.relate(eli, ids.entity("srl")).as("employedBy005Srl").effectiveFrom(july1))
                .build());
    }

    /**
     * HR-4. Recorded 15 April: correction, Dev's salary was 72,000 from 1 January.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr4DevSalaryCorrection(Ids ids) {
        return new Recording(on(4, 15), Transaction.by(HR_ADMIN).reason("Dev's salary was 72,000")
                .write(Employment.patch(ids.entity("e004")).annualSalary(new BigDecimal("72000"))
                        .effectiveFrom(JANUARY_1))
                .build());
    }

    // ------------------------------------------------------------------ HR-5 to HR-8: leave and rules

    /**
     * HR-5, the request. Recorded 1 March: Dev requests vacation from 16 to 20 March, 5 days. The request is named
     * {@code devVacation} and its relationship to Dev {@code devHasVacation}.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr5DevRequestsVacation(Ids ids) {
        Instant filed = on(3, 1);
        return new Recording(filed, Transaction.by(HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 3, 16))
                        .lastDay(LocalDate.of(2026, 3, 20)).days(new BigDecimal("5")).status("requested")
                        .as("devVacation").effectiveFrom(filed))
                .write(HasLeaveRequest.relate(ids.entity("e004"), new TemporaryId("devVacation"))
                        .as("devHasVacation").effectiveFrom(filed))
                .build());
    }

    /**
     * HR-5, the approval. Recorded 2 March: Cleo approves the request. The status is {@code approved} from 2 March,
     * and Cleo's employment decides it from the same day; the decision is named {@code devVacationDecision}.
     *
     * @param ids the IDs of HR-1, HR-2 and the request
     * @return the recording
     */
    public static Recording hr5CleoApproves(Ids ids) {
        Instant decided = on(3, 2);
        return new Recording(decided, Transaction.by(HR_ADMIN)
                .write(LeaveRequest.patch(ids.entity("devVacation")).status("approved").effectiveFrom(decided))
                .write(DecidedBy.relate(ids.entity("devVacation"), ids.entity("e003")).as("devVacationDecision")
                        .effectiveFrom(decided))
                .build());
    }

    /**
     * HR-6. Recorded 3 March: Eli's vacation request, 20 to 24 April, is approved by Gus, who manages none of Eli's
     * units. The commit is rejected by the rule {@code decided-by-manager}. The decision is named
     * {@code eliVacationDecision}.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr6EliRequestDecidedByGus(Ids ids) {
        Instant filed = on(3, 3);
        return new Recording(filed, Transaction.by(HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 4, 20))
                        .lastDay(LocalDate.of(2026, 4, 24)).days(new BigDecimal("5")).status("approved")
                        .as("eliVacation").effectiveFrom(filed))
                .write(HasLeaveRequest.relate(ids.entity("e005"), new TemporaryId("eliVacation"))
                        .as("eliHasVacation").effectiveFrom(filed))
                .write(DecidedBy.relate(new TemporaryId("eliVacation"), ids.entity("e007"))
                        .as("eliVacationDecision").effectiveFrom(filed))
                .build());
    }

    /**
     * HR-7. Recorded 4 March: Dev's approved vacation of 21 more days, the working days from 3 to 31 August, decided
     * by Cleo. With the 5 days of HR-5 it makes 26 days in 2026, so the commit is rejected by the rule
     * {@code vacation-within-entitlement}. The request is named {@code devAugust}.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr7DevSecondVacation(Ids ids) {
        return devAugustVacation(ids, 21);
    }

    /**
     * Dev's approved vacation in August, recorded on 4 March like HR-7, for a number of days: the same transaction with
     * another length. With 20 days Dev's year adds up to exactly 25, the most the rule allows.
     *
     * @param ids  the IDs of HR-1 and HR-2
     * @param days the days of the vacation, which the request states; its dates are 3 to 31 August whatever the days
     * @return the recording
     */
    public static Recording devAugustVacation(Ids ids, int days) {
        Instant filed = on(3, 4);
        return new Recording(filed, Transaction.by(HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 8, 3))
                        .lastDay(LocalDate.of(2026, 8, 31)).days(new BigDecimal(days)).status("approved")
                        .as("devAugust").effectiveFrom(filed))
                .write(HasLeaveRequest.relate(ids.entity("e004"), new TemporaryId("devAugust"))
                        .as("devHasAugust").effectiveFrom(filed))
                .write(DecidedBy.relate(new TemporaryId("devAugust"), ids.entity("e003"))
                        .as("devAugustDecision").effectiveFrom(filed))
                .build());
    }

    /**
     * Cleo's own vacation, 4 to 8 May, 5 days, decided by the employment with the key given: Cleo herself, which the
     * rule {@code decided-by-manager} refuses, or Ben, who manages Engineering above Platform, which it accepts. The
     * story tells nothing about it: tests only dry-run it. The request is named {@code cleoVacation}.
     *
     * @param ids      the IDs of HR-1 and HR-2
     * @param deciding the key of the deciding employment: {@code e003} (Cleo) or {@code e002} (Ben)
     * @return the recording, dated 3 March
     */
    public static Recording cleoVacationDecidedBy(Ids ids, String deciding) {
        Instant filed = on(3, 3);
        return new Recording(filed, Transaction.by(HR_ADMIN)
                .write(LeaveRequest.create().kind("vacation").firstDay(LocalDate.of(2026, 5, 4))
                        .lastDay(LocalDate.of(2026, 5, 8)).days(new BigDecimal("5")).status("approved")
                        .as("cleoVacation").effectiveFrom(filed))
                .write(HasLeaveRequest.relate(ids.entity("e003"), new TemporaryId("cleoVacation"))
                        .as("cleoHasVacation").effectiveFrom(filed))
                .write(DecidedBy.relate(new TemporaryId("cleoVacation"), ids.entity(deciding))
                        .as("cleoVacationDecision").effectiveFrom(filed))
                .build());
    }

    /**
     * HR-8. Recorded 5 March: making Brightwater part of Platform, which is below Engineering, which is below
     * Brightwater. The commit is rejected by the rule {@code no-unit-cycles}. The relationship is named
     * {@code bwPartOfPlt}.
     *
     * @param ids the IDs of HR-1
     * @return the recording
     */
    public static Recording hr8BrightwaterPartOfPlatform(Ids ids) {
        Instant recorded = on(3, 5);
        return new Recording(recorded, Transaction.by(HR_ADMIN)
                .write(PartOf.relate(ids.entity("bw"), ids.entity("plt")).as("bwPartOfPlt").effectiveFrom(recorded))
                .build());
    }

    /**
     * HR-16. Recorded 9 March: Dev asks to move his approved vacation of HR-5 to 23 to 27 March, the same 5 days a week
     * later. The change is effective from the day it is recorded, and the request keeps its kind, its days and its
     * status. The commit is rejected by the rule {@code decided-requests-stay}: the request was approved before the
     * transaction.
     *
     * @param ids the IDs of HR-1, HR-2 and the request
     * @return the recording
     */
    public static Recording hr16DevMovesApprovedVacation(Ids ids) {
        Instant recorded = on(3, 9);
        return new Recording(recorded, Transaction.by(HR_ADMIN).reason("Dev moves his vacation to 23 to 27 March")
                .write(LeaveRequest.patch(ids.entity("devVacation")).firstDay(LocalDate.of(2026, 3, 23))
                        .lastDay(LocalDate.of(2026, 3, 27)).effectiveFrom(recorded))
                .build());
    }

    // ------------------------------------------------------------------ HR-11 and HR-12: sick leave, leaving, erasure

    /**
     * HR-11, the sick leave. Recorded 20 May: Gus was sick on 11 and 12 May, 2 days, approved by Fay, who manages
     * Sales. The request is effective from 11 May, its first day, and is named {@code gusSick}; the decision is named
     * {@code gusSickDecision}.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr11GusWasSick(Ids ids) {
        Instant firstDay = on(5, 11);
        return new Recording(on(5, 20), Transaction.by(HR_ADMIN)
                .write(LeaveRequest.create().kind("sick").firstDay(LocalDate.of(2026, 5, 11))
                        .lastDay(LocalDate.of(2026, 5, 12)).days(new BigDecimal("2")).status("approved")
                        .as("gusSick").effectiveFrom(firstDay))
                .write(HasLeaveRequest.relate(ids.entity("e007"), new TemporaryId("gusSick")).as("gusHasSick")
                        .effectiveFrom(firstDay))
                .write(DecidedBy.relate(new TemporaryId("gusSick"), ids.entity("e006")).as("gusSickDecision")
                        .effectiveFrom(firstDay))
                .build());
    }

    /**
     * HR-11, the leaving. Recorded 20 June: Gus leaves on 30 June, so his employment ends from 1 July, with its own
     * relationships. The transaction does not name the sick leave or its decision: the sick leave is a target of the
     * composition from his employment, so it ends with it, and so does the decision by Fay, a relationship of the
     * request that the cascade ends with the request.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the recording
     */
    public static Recording hr11GusLeaves(Ids ids) {
        Instant july1 = on(7, 1);
        return new Recording(on(6, 20), Transaction.by(HR_ADMIN).reason("Gus leaves on 30 June")
                .endDate(ids.entity("e007"), july1)
                .endDate(ids.relationship("employmentOf007"), july1)
                .endDate(ids.relationship("employedBy007"), july1)
                .endDate(ids.relationship("worksAt007"), july1)
                .endDate(ids.relationship("fills007"), july1)
                .build());
    }

    /**
     * HR-12, the refused request. The principal {@code dpo} first purges only Gus's person, which the store refuses:
     * his employment would be left without the {@code EmploymentOf} that it requires.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the purge
     */
    public static Purge hr12ErasureOfGusPersonOnly(Ids ids) {
        return Purge.by(DPO).reason(ERASURE_TICKET).entity(ids.entity("gus")).build();
    }

    /**
     * HR-12. The principal {@code dpo} erases Gus's personal data under the ticket {@code DSR-2026-0042}: his person
     * and employment are purged, and with them, by the composition, his sick leave and its decision by Fay. The
     * ticket, not his name, is the reason: purge records keep the principal and the reason.
     *
     * @param ids the IDs of HR-1 and HR-2
     * @return the purge
     */
    public static Purge hr12ErasureOfGus(Ids ids) {
        return Purge.by(DPO).reason(ERASURE_TICKET)
                .entity(ids.entity("gus"))
                .entity(ids.entity("e007"))
                .build();
    }

    // ------------------------------------------------------------------ HR-13: the agent's payload

    /**
     * HR-13. The payload that the agent {@code agent:hr-assistant} writes: Hana Kim is hired from 1 August as a
     * Software Engineer, employment E-008 at 72,000 EUR, in a new position P-213 in Platform, in Berlin, employed by
     * Brightwater GmbH. The agent names the existing unit, job, location and employer by the unique keys that it knows,
     * the code of the unit and of the job and the names of the location and of the employer, and so needs no query to
     * find their IDs (ADR-0026): the store resolves each key when it commits.
     *
     * @return the payload as JSON text
     */
    public static String hr13HanaPayload() {
        return """
                {
                  "cairn": 1,
                  "principal": "%s",
                  "reason": "Hire Hana Kim as a Software Engineer in Platform",
                  "writes": [
                    { "create": { "temporaryId": "p213", "entityType": "Position", "from": "2026-08-01T00:00:00Z",
                        "values": { "code": "P-213", "title": "Software Engineer", "fte": 1 } } },
                    { "relate": { "relationshipType": "BelongsTo", "source": "$p213",
                        "target": { "entityType": "OrgUnit", "key": { "code": "PLT" } },
                        "from": "2026-08-01T00:00:00Z" } },
                    { "relate": { "relationshipType": "OfJob", "source": "$p213",
                        "target": { "entityType": "Job", "key": { "code": "SWE" } },
                        "from": "2026-08-01T00:00:00Z" } },
                    { "create": { "temporaryId": "hana", "entityType": "Person", "from": "2026-08-01T00:00:00Z",
                        "values": { "givenName": "Hana", "familyName": "Kim" } } },
                    { "create": { "temporaryId": "e008", "entityType": "Employment", "from": "2026-08-01T00:00:00Z",
                        "values": { "employeeNumber": "E-008", "kind": "permanent", "weeklyHours": 40,
                                    "annualSalary": 72000, "currency": "EUR" } } },
                    { "relate": { "relationshipType": "EmploymentOf", "source": "$e008", "target": "$hana",
                        "from": "2026-08-01T00:00:00Z" } },
                    { "relate": { "relationshipType": "EmployedBy", "source": "$e008",
                        "target": { "entityType": "LegalEntity", "key": { "name": "Brightwater GmbH" } },
                        "from": "2026-08-01T00:00:00Z" } },
                    { "relate": { "relationshipType": "WorksAt", "source": "$e008",
                        "target": { "entityType": "Location", "key": { "name": "Berlin" } },
                        "from": "2026-08-01T00:00:00Z" } },
                    { "relate": { "relationshipType": "Fills", "source": "$e008", "target": "$p213",
                        "from": "2026-08-01T00:00:00Z" } }
                  ]
                }
                """.formatted(HR_ASSISTANT);
    }

    // ------------------------------------------------------------------ dates

    /**
     * Midnight UTC on a day of 2026.
     *
     * @param month the month, from 1
     * @param day   the day of the month, from 1
     * @return the instant
     */
    public static Instant on(int month, int day) {
        return LocalDate.of(2026, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String resource(InputStream stream, String name) {
        if (stream == null) {
            throw new IllegalStateException("The resource " + name + " is missing from the module.");
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The resource " + name + " cannot be read.", e);
        }
    }
}
