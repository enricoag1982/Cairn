package io.github.enricoag1982.cairn.example.hr;

import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
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
import io.github.enricoag1982.cairn.example.hr.model.OfJob;
import io.github.enricoag1982.cairn.example.hr.model.OrgUnit;
import io.github.enricoag1982.cairn.example.hr.model.PartOf;
import io.github.enricoag1982.cairn.example.hr.model.Person;
import io.github.enricoag1982.cairn.example.hr.model.Position;
import io.github.enricoag1982.cairn.example.hr.model.WorksAt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Generates a company for the story HR-14: 40 employees with a year of hires, transfers, pay changes, leave and
 * terminations, in the units, positions, locations and employers of a larger Brightwater, from a seed. The same seed
 * gives the same company, because the generator draws from a {@code java.util.random} algorithm that is specified,
 * {@code L64X128MixRandom}, and from nothing else: no clock, no hash order.
 *
 * <p>The company is {@link Step steps}, one transaction each, to commit in order. A step builds its transaction when it
 * is committed, because it refers to what earlier steps created by the names {@link Ids} keeps. The generator also
 * simulates the company while it generates it, so it knows what the company looks like at the end of each month
 * ({@link MonthEnd}), which a store must then answer too. Generated data follows the rules of the domain: every leave
 * is decided by a manager of the requester's unit or of a unit above it who is employed on the request's first day, a
 * vacation never takes the approved days of a year above 25, and the manager of the root unit stays.
 *
 * <p>The first termination of the year is that of a manager of another unit, if one can leave on the day: one who
 * decided no request with a first day on or after it, so that the decisions of the manager stay as they were taken
 * (ADR-0034). The unit then has no manager, and a later request in it goes to a manager of a unit above.
 *
 * <p>The year is 2026. Hires of the first month are effective from 1 January and recorded on 2 January; every later
 * event is recorded on the day it takes effect, and no two events fall on the same day.
 */
public final class CompanyGenerator {

    /** The seed of the company of the story HR-14. */
    public static final long SEED = 42;

    /** The number of employees the company hires over the year. */
    public static final int EMPLOYEES = 40;

    /** The principal that records the generated company. */
    public static final String PRINCIPAL = "hr-import";

    private static final String ALGORITHM = "L64X128MixRandom";
    private static final int INITIAL_EMPLOYEES = 20;
    private static final int YEAR = 2026;
    private static final int VACATION_ENTITLEMENT = 25;

    private static final List<String> GIVEN_NAMES = List.of("Aino", "Bram", "Chiara", "Dario", "Elin", "Farid", "Greta",
            "Hugo", "Ines", "Jonas", "Katya", "Luca", "Mira", "Nils", "Olga", "Pavel", "Quinn", "Rosa", "Sven",
            "Tara", "Ugo", "Vera", "Wim", "Xenia", "Yara", "Zane", "Adele", "Boris", "Clara", "Dmitri", "Edda",
            "Felix", "Gianna", "Hendrik", "Iris", "Jules", "Klara", "Leon", "Marta", "Nico");
    private static final List<String> FAMILY_NAMES = List.of("Albrecht", "Bianchi", "Costa", "Dubois", "Eriksson",
            "Fischer", "Garcia", "Haas", "Ivanov", "Jansen", "Kovacs", "Lehmann", "Moreno", "Nielsen", "Olsen",
            "Petrov", "Quast", "Ricci", "Silva", "Teixeira", "Ulrich", "Vogel", "Weber", "Xavier", "Yilmaz",
            "Zimmer", "Amato", "Becker", "Conti", "Dietrich", "Esposito", "Franke", "Greco", "Hartmann", "Ibarra",
            "Jung", "Keller", "Lopes", "Marino", "Neumann");

    private record UnitSpec(String code, String name, String parent) {
    }

    private static final List<UnitSpec> UNITS = List.of(
            new UnitSpec("BW", "Brightwater", null),
            new UnitSpec("ENG", "Engineering", "BW"),
            new UnitSpec("SAL", "Sales", "BW"),
            new UnitSpec("OPS", "Operations", "BW"),
            new UnitSpec("PLT", "Platform", "ENG"),
            new UnitSpec("QA", "Quality", "ENG"));

    /** How many regular positions, besides the manager's, each unit has. */
    private static final Map<String, Integer> REGULAR_POSITIONS = Map.of(
            "BW", 0, "ENG", 5, "SAL", 9, "OPS", 9, "PLT", 9, "QA", 6);

    private record JobSpec(String code, String title, int minSalary, int maxSalary) {
    }

    private static final Map<String, JobSpec> JOBS = Map.of(
            "MGR", new JobSpec("MGR", "Manager", 100_000, 140_000),
            "SWE", new JobSpec("SWE", "Software Engineer", 60_000, 95_000),
            "AE", new JobSpec("AE", "Account Executive", 50_000, 80_000),
            "SPC", new JobSpec("SPC", "Operations Specialist", 45_000, 65_000));

    private static final Map<String, String> REGULAR_JOB = Map.of(
            "ENG", "SWE", "PLT", "SWE", "QA", "SWE", "SAL", "AE", "OPS", "SPC");

    private record PlaceSpec(String name, String city, String country, String employer) {
    }

    private static final List<PlaceSpec> PLACES = List.of(
            new PlaceSpec("Berlin", "Berlin", "DE", "Brightwater GmbH"),
            new PlaceSpec("Milan", "Milan", "IT", "Brightwater S.r.l."),
            new PlaceSpec("Lisbon", "Lisbon", "PT", "Brightwater Lda"));

    /**
     * One transaction of the company and when it is recorded.
     *
     * @param recordedAt when the transaction is recorded
     * @param builder    builds the transaction from the IDs of what earlier steps created
     */
    public record Step(Instant recordedAt, Function<Ids, Transaction> builder) {

        /**
         * Builds the transaction.
         *
         * @param ids the IDs of what the earlier steps created, kept by name
         * @return the transaction
         */
        public Transaction transaction(Ids ids) {
            return builder.apply(ids);
        }
    }

    /**
     * What the company looks like at the end of a month, as the generator simulated it.
     *
     * @param effectiveAt     the last microsecond of the month
     * @param employments     the number of employments in force
     * @param headcountByUnit for each unit with at least one employee, its code and the number of employments that fill
     *                        positions in it or in the units below it
     */
    public record MonthEnd(Instant effectiveAt, int employments, Map<String, Integer> headcountByUnit) {

        /**
         * Copies the headcounts, ordered by code.
         */
        public MonthEnd {
            headcountByUnit = Collections.unmodifiableMap(new TreeMap<>(headcountByUnit));
        }
    }

    /**
     * A generated company.
     *
     * @param steps     the transactions, in the order to commit them
     * @param monthEnds the state of the company at the end of each of the 12 months
     * @param events    how many events of each kind the year has, by name: {@code hires}, {@code transfers},
     *                  {@code payChanges}, {@code leaves} and {@code terminations}
     */
    public record Company(List<Step> steps, List<MonthEnd> monthEnds, Map<String, Integer> events) {

        /**
         * Copies the lists and the counts.
         */
        public Company {
            steps = List.copyOf(steps);
            monthEnds = List.copyOf(monthEnds);
            events = Map.copyOf(events);
        }
    }

    private CompanyGenerator() {
    }

    /**
     * Generates the company of a seed.
     *
     * @param seed the seed; the same seed gives the same company
     * @return the company
     */
    public static Company generate(long seed) {
        return new Generation(RandomGeneratorFactory.<RandomGenerator>of(ALGORITHM).create(seed)).run();
    }

    // ------------------------------------------------------------------ the simulation

    /** A position of the company, and the employee who fills it now. */
    private static final class Slot {
        final String key;
        final String unit;
        final String job;
        Employee occupant;

        Slot(String key, String unit, String job) {
            this.key = key;
            this.unit = unit;
            this.job = job;
        }
    }

    /** An employee as the simulation knows them, with the names of the relationships that are in force. */
    private static final class Employee {
        final int number;
        final String code;
        final String key;
        final boolean manager;
        final LocalDate hired;
        Slot slot;
        PlaceSpec place;
        BigDecimal salary;
        boolean active = true;
        /** The latest first day of the leave requests that this employee decided: a manager leaves only after it. */
        LocalDate decidedUntil = LocalDate.MIN;
        int vacationDays;
        String employmentOf;
        String employedBy;
        String worksAt;
        String fills;
        private int names;

        Employee(int number, boolean manager, LocalDate hired, Slot slot, PlaceSpec place, BigDecimal salary) {
            this.number = number;
            this.code = String.format(Locale.ROOT, "E-%03d", number);
            this.key = String.format(Locale.ROOT, "e%03d", number);
            this.manager = manager;
            this.hired = hired;
            this.slot = slot;
            this.place = place;
            this.salary = salary;
        }

        /** A name for a relationship of this employee that no other relationship has. */
        String next(String type) {
            return type + String.format(Locale.ROOT, "%03d", number) + "." + ++names;
        }
    }

    private enum Kind {
        HIRE, TRANSFER, PAY_CHANGE, LEAVE, TERMINATION
    }

    private static final class Generation {
        private final RandomGenerator random;
        private final List<Step> steps = new ArrayList<>();
        private final List<MonthEnd> monthEnds = new ArrayList<>();
        private final List<Slot> slots = new ArrayList<>();
        private final List<Employee> employees = new ArrayList<>();
        private final List<String> givenNames;
        private final List<String> familyNames;
        private final Map<String, Integer> events = new TreeMap<>();
        private int leaves;
        private boolean managerLeft;

        Generation(RandomGenerator random) {
            this.random = random;
            this.givenNames = shuffled(GIVEN_NAMES);
            this.familyNames = shuffled(FAMILY_NAMES);
        }

        Company run() {
            structure();
            int[] hires = hiresPerMonth();
            for (int month = 1; month <= 12; month++) {
                if (month == 1) {
                    initialHires();
                }
                month(month, hires[month - 1]);
                monthEnds.add(monthEnd(month));
            }
            return new Company(steps, monthEnds, events);
        }

        private List<String> shuffled(List<String> names) {
            List<String> copy = new ArrayList<>(names);
            Collections.shuffle(copy, random);
            return copy;
        }

        // -------------------------------------------------------------- structure and hires

        private void structure() {
            int number = 1;
            for (UnitSpec unit : UNITS) {
                slots.add(new Slot(String.format(Locale.ROOT, "p%03d", number++), unit.code(), "MGR"));
            }
            for (UnitSpec unit : UNITS) {
                for (int i = 0; i < REGULAR_POSITIONS.get(unit.code()); i++) {
                    slots.add(new Slot(String.format(Locale.ROOT, "p%03d", number++), unit.code(),
                            REGULAR_JOB.get(unit.code())));
                }
            }
            steps.add(new Step(instant(LocalDate.of(YEAR, 1, 2)), ids -> structureTransaction()));
        }

        private Transaction structureTransaction() {
            Instant start = instant(LocalDate.of(YEAR, 1, 1));
            Transaction.Builder transaction = Transaction.by(PRINCIPAL).reason("The structure of the company");
            for (PlaceSpec place : PLACES) {
                transaction.write(LegalEntity.create().name(place.employer()).country(place.country())
                        .as("employer" + place.country()).effectiveFrom(start));
                transaction.write(Location.create().name(place.name()).city(place.city()).country(place.country())
                        .as("location" + place.name()).effectiveFrom(start));
            }
            for (JobSpec job : JOBS.values().stream().sorted(Comparator.comparing(JobSpec::code)).toList()) {
                transaction.write(Job.create().code(job.code()).title(job.title()).as("job" + job.code())
                        .effectiveFrom(start));
            }
            for (UnitSpec unit : UNITS) {
                transaction.write(OrgUnit.create().code(unit.code()).name(unit.name()).as("unit" + unit.code())
                        .effectiveFrom(start));
            }
            for (UnitSpec unit : UNITS) {
                if (unit.parent() != null) {
                    transaction.write(PartOf.relate(new TemporaryId("unit" + unit.code()),
                            new TemporaryId("unit" + unit.parent())).as("partOf" + unit.code()).effectiveFrom(start));
                }
            }
            for (Slot slot : slots) {
                transaction.write(Position.create().code("P-" + slot.key.substring(1))
                        .title(JOBS.get(slot.job).title() + " in " + slot.unit).fte(BigDecimal.ONE).as(slot.key)
                        .effectiveFrom(start));
                transaction.write(BelongsTo.relate(new TemporaryId(slot.key), new TemporaryId("unit" + slot.unit))
                        .effectiveFrom(start));
                transaction.write(OfJob.relate(new TemporaryId(slot.key), new TemporaryId("job" + slot.job))
                        .effectiveFrom(start));
            }
            return transaction.build();
        }

        /** How many employees each month hires: 20 in January, at least one in every later month, 40 in all. */
        private int[] hiresPerMonth() {
            int[] hires = new int[12];
            hires[0] = INITIAL_EMPLOYEES;
            for (int month = 1; month < 12; month++) {
                hires[month] = 1;
            }
            for (int extra = EMPLOYEES - INITIAL_EMPLOYEES - 11; extra > 0; extra--) {
                hires[1 + random.nextInt(11)]++;
            }
            return hires;
        }

        /** The 20 employees of 1 January: the six managers, and 14 more in random positions. */
        private void initialHires() {
            LocalDate start = LocalDate.of(YEAR, 1, 1);
            Instant recorded = instant(LocalDate.of(YEAR, 1, 2));
            for (Slot slot : slots) {
                if (slot.job.equals("MGR")) {
                    hire(slot, start, recorded);
                }
            }
            for (int i = INITIAL_EMPLOYEES - UNITS.size(); i > 0; i--) {
                hire(randomVacancy(), start, recorded);
            }
        }

        private Slot randomVacancy() {
            List<Slot> vacant = slots.stream().filter(s -> s.occupant == null && !s.job.equals("MGR")).toList();
            return vacant.get(random.nextInt(vacant.size()));
        }

        private void hire(Slot slot, LocalDate effective, Instant recorded) {
            JobSpec job = JOBS.get(slot.job);
            BigDecimal salary = BigDecimal.valueOf(job.minSalary()
                    + 1000L * random.nextInt((job.maxSalary() - job.minSalary()) / 1000 + 1));
            Employee employee = new Employee(employees.size() + 1, slot.job.equals("MGR"), effective, slot,
                    PLACES.get(random.nextInt(PLACES.size())), salary);
            employees.add(employee);
            slot.occupant = employee;
            employee.employmentOf = "employmentOf" + String.format(Locale.ROOT, "%03d", employee.number);
            employee.employedBy = employee.next("employedBy");
            employee.worksAt = employee.next("worksAt");
            employee.fills = employee.next("fills");
            LocalDate birthDate = LocalDate.of(1965 + random.nextInt(36), 1 + random.nextInt(12),
                    1 + random.nextInt(28));
            String given = givenNames.get(employee.number - 1);
            String family = familyNames.get(employee.number - 1);
            // The step reads what the employee is now, not what they become later: copy it.
            String code = employee.code;
            String key = employee.key;
            String employmentOf = employee.employmentOf;
            String employedBy = employee.employedBy;
            String worksAt = employee.worksAt;
            String fills = employee.fills;
            PlaceSpec place = employee.place;
            boolean manager = employee.manager;
            String position = slot.key;
            String unit = slot.unit;
            Instant from = instant(effective);
            events.merge("hires", 1, Integer::sum);
            steps.add(new Step(recorded, ids -> {
                TemporaryId employment = new TemporaryId(key);
                TemporaryId person = new TemporaryId("person" + key.substring(1));
                Transaction.Builder transaction = Transaction.by(PRINCIPAL).reason("Hire " + code)
                        .write(Person.create().givenName(given).familyName(family).birthDate(birthDate)
                                .as(person.name()).effectiveFrom(from))
                        .write(Employment.create().employeeNumber(code).kind("permanent").weeklyHours(40)
                                .annualSalary(salary).currency("EUR").as(key).effectiveFrom(from))
                        .write(EmploymentOf.relate(employment, person).as(employmentOf).effectiveFrom(from))
                        .write(EmployedBy.relate(employment, ids.entity("employer" + place.country()))
                                .as(employedBy).effectiveFrom(from))
                        .write(WorksAt.relate(employment, ids.entity("location" + place.name())).as(worksAt)
                                .effectiveFrom(from))
                        .write(Fills.relate(employment, ids.entity(position)).as(fills).effectiveFrom(from));
                if (manager) {
                    transaction.write(ManagedBy.relate(ids.entity("unit" + unit), employment)
                            .as("managedBy" + unit).effectiveFrom(from));
                }
                return transaction.build();
            }));
        }

        // -------------------------------------------------------------- a month

        /** Draws a month's events and the distinct days they fall on, and applies them in the order of the days. */
        private void month(int month, int hires) {
            List<Kind> kinds = new ArrayList<>();
            kinds.addAll(Collections.nCopies(month == 1 ? 0 : hires, Kind.HIRE));
            kinds.addAll(Collections.nCopies(random.nextInt(3), Kind.TRANSFER));
            kinds.addAll(Collections.nCopies(random.nextInt(3), Kind.PAY_CHANGE));
            kinds.addAll(Collections.nCopies(1 + random.nextInt(3), Kind.LEAVE));
            if (month >= 4 && random.nextInt(10) < 6) {
                kinds.add(Kind.TERMINATION);
            }
            Collections.shuffle(kinds, random);
            List<Integer> days = new ArrayList<>();
            for (int day = month == 1 ? 5 : 2; day <= 27; day++) {
                days.add(day);
            }
            Collections.shuffle(days, random);
            List<Integer> chosen = new ArrayList<>(days.subList(0, kinds.size()));
            Collections.sort(chosen);
            for (int i = 0; i < kinds.size(); i++) {
                LocalDate day = LocalDate.of(YEAR, month, chosen.get(i));
                switch (kinds.get(i)) {
                    case HIRE -> hire(randomVacancy(), day, instant(day));
                    case TRANSFER -> transfer(day);
                    case PAY_CHANGE -> payChange(day);
                    case LEAVE -> leave(day);
                    case TERMINATION -> terminate(day);
                }
            }
        }

        /** The employees who can take leave, move or leave: in force, not managers, hired on an earlier day. */
        private List<Employee> staff(LocalDate day) {
            return employees.stream().filter(e -> e.active && !e.manager && e.hired.isBefore(day)).toList();
        }

        /** An employee moves to a vacant position in another unit, and perhaps to another location and employer. */
        private void transfer(LocalDate day) {
            List<Employee> movers = staff(day);
            if (movers.isEmpty()) {
                return;
            }
            Employee employee = movers.get(random.nextInt(movers.size()));
            List<Slot> vacant = slots.stream().filter(s -> s.occupant == null && !s.job.equals("MGR")
                    && !s.unit.equals(employee.slot.unit)).toList();
            if (vacant.isEmpty()) {
                return;
            }
            Slot target = vacant.get(random.nextInt(vacant.size()));
            PlaceSpec place = PLACES.get(random.nextInt(PLACES.size()));
            boolean relocates = !place.equals(employee.place);

            String oldFills = employee.fills;
            String oldWorksAt = employee.worksAt;
            String oldEmployedBy = employee.employedBy;
            employee.slot.occupant = null;
            employee.slot = target;
            target.occupant = employee;
            employee.fills = employee.next("fills");
            if (relocates) {
                employee.place = place;
                employee.worksAt = employee.next("worksAt");
                employee.employedBy = employee.next("employedBy");
            }
            String newFills = employee.fills;
            String newWorksAt = employee.worksAt;
            String newEmployedBy = employee.employedBy;
            String key = employee.key;
            String code = employee.code;
            String position = target.key;
            Instant from = instant(day);
            events.merge("transfers", 1, Integer::sum);
            steps.add(new Step(from, ids -> {
                Transaction.Builder transaction = Transaction.by(PRINCIPAL).reason("Transfer " + code)
                        .endDate(ids.relationship(oldFills), from)
                        .write(Fills.relate(ids.entity(key), ids.entity(position)).as(newFills).effectiveFrom(from));
                if (relocates) {
                    transaction.endDate(ids.relationship(oldWorksAt), from)
                            .write(WorksAt.relate(ids.entity(key), ids.entity("location" + place.name()))
                                    .as(newWorksAt).effectiveFrom(from))
                            .endDate(ids.relationship(oldEmployedBy), from)
                            .write(EmployedBy.relate(ids.entity(key), ids.entity("employer" + place.country()))
                                    .as(newEmployedBy).effectiveFrom(from));
                }
                return transaction.build();
            }));
        }

        /** An employee's salary rises by 2 to 8%, rounded to 100. */
        private void payChange(LocalDate day) {
            List<Employee> active = employees.stream().filter(e -> e.active && e.hired.isBefore(day)).toList();
            if (active.isEmpty()) {
                return;
            }
            Employee employee = active.get(random.nextInt(active.size()));
            BigDecimal raised = employee.salary.multiply(BigDecimal.valueOf(100 + 2 + random.nextInt(7)))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            employee.salary = raised;
            String key = employee.key;
            String code = employee.code;
            Instant from = instant(day);
            events.merge("payChanges", 1, Integer::sum);
            steps.add(new Step(from, ids -> Transaction.by(PRINCIPAL).reason("Pay change for " + code)
                    .write(Employment.patch(ids.entity(key)).annualSalary(raised).effectiveFrom(from))
                    .build()));
        }

        /**
         * An employee files a leave that a manager of the employee's unit, or of a unit above it, approves in the same
         * transaction. The first day is two to nine days after the day it is filed. A vacation that would take the
         * year above its entitlement becomes sick leave.
         */
        private void leave(LocalDate day) {
            List<Employee> people = staff(day);
            if (people.isEmpty()) {
                return;
            }
            Employee employee = people.get(random.nextInt(people.size()));
            int roll = random.nextInt(10);
            String kind = roll < 6 ? "vacation" : roll < 9 ? "sick" : "unpaid";
            int length = 1 + random.nextInt(kind.equals("vacation") ? 5 : 3);
            if (kind.equals("vacation")) {
                if (employee.vacationDays + length > VACATION_ENTITLEMENT) {
                    kind = "sick";
                } else {
                    employee.vacationDays += length;
                }
            }
            LocalDate firstDay = day.plusDays(2 + random.nextInt(8));
            if (firstDay.getYear() != YEAR) {
                firstDay = LocalDate.of(YEAR, 12, 31);
            }
            LocalDate lastDay = firstDay.plusDays(length - 1);
            List<Employee> deciders = managersAbove(employee.slot.unit);
            Employee decider = deciders.get(random.nextInt(deciders.size()));
            if (firstDay.isAfter(decider.decidedUntil)) {
                decider.decidedUntil = firstDay;
            }

            int leave = ++leaves;
            String request = "leave" + leave;
            String decision = "decision" + leave;
            String requester = employee.key;
            String code = employee.code;
            String deciding = decider.key;
            Instant filed = instant(day);
            String requestKind = kind;
            LocalDate first = firstDay;
            events.merge("leaves", 1, Integer::sum);
            steps.add(new Step(filed, ids -> Transaction.by(PRINCIPAL).reason("Leave of " + code)
                    .write(LeaveRequest.create().kind(requestKind).firstDay(first).lastDay(lastDay)
                            .days(BigDecimal.valueOf(length)).status("approved").as(request).effectiveFrom(filed))
                    .write(HasLeaveRequest.relate(ids.entity(requester), new TemporaryId(request))
                            .as("hasLeave" + leave).effectiveFrom(filed))
                    .write(DecidedBy.relate(new TemporaryId(request), ids.entity(deciding)).as(decision)
                            .effectiveFrom(filed))
                    .build()));
        }

        /** The managers of a unit and of the units above it, nearest first; a unit whose manager left has none. */
        private List<Employee> managersAbove(String unit) {
            List<Employee> managers = new ArrayList<>();
            for (String code = unit; code != null; code = parentOf(code)) {
                String current = code;
                slots.stream().filter(s -> s.unit.equals(current) && s.job.equals("MGR")).findFirst()
                        .map(s -> s.occupant).ifPresent(managers::add);
            }
            return managers;
        }

        private static String parentOf(String unit) {
            return UNITS.stream().filter(u -> u.code().equals(unit)).findFirst().orElseThrow().parent();
        }

        /**
         * The managers who can leave on a day: those of the units but the root, who decided no request whose first day
         * is on or after the day. The decision of a manager who left before the first day of a request would no longer
         * meet {@code decided-by-manager}, which reads who manages the unit on that day.
         */
        private List<Employee> managersWhoCanLeave(LocalDate day) {
            String root = UNITS.getFirst().code();
            return employees.stream().filter(e -> e.active && e.manager && !e.slot.unit.equals(root)
                    && e.decidedUntil.isBefore(day)).toList();
        }

        /**
         * An employee who was hired at least 20 days ago leaves, or, the first time, a manager: the employment and what
         * hangs on it end. A manager's employment ends with the {@code ManagedBy} of the unit, and the decisions that
         * the manager took stay, as records of past facts.
         */
        private void terminate(LocalDate day) {
            List<Employee> managers = managersWhoCanLeave(day);
            List<Employee> leavers = staff(day).stream().filter(e -> e.hired.isBefore(day.minusDays(20))).toList();
            Employee employee;
            if (!managerLeft && !managers.isEmpty()) {
                employee = managers.get(random.nextInt(managers.size()));
                managerLeft = true;
            } else if (leavers.isEmpty()) {
                return;
            } else {
                employee = leavers.get(random.nextInt(leavers.size()));
            }
            employee.active = false;
            employee.slot.occupant = null;
            String key = employee.key;
            String code = employee.code;
            List<String> relationships = new ArrayList<>(List.of(employee.employmentOf, employee.employedBy,
                    employee.worksAt, employee.fills));
            if (employee.manager) {
                relationships.add("managedBy" + employee.slot.unit);
            }
            Instant from = instant(day);
            events.merge("terminations", 1, Integer::sum);
            steps.add(new Step(from, ids -> {
                Transaction.Builder transaction = Transaction.by(PRINCIPAL).reason(code + " leaves")
                        .endDate(ids.entity(key), from);
                relationships.forEach(name -> transaction.endDate(ids.relationship(name), from));
                return transaction.build();
            }));
        }

        // -------------------------------------------------------------- the end of a month

        private MonthEnd monthEnd(int month) {
            Instant lastMicrosecond = instant(YearMonth.of(YEAR, month).plusMonths(1).atDay(1))
                    .minus(1, ChronoUnit.MICROS);
            Map<String, Integer> headcount = new TreeMap<>();
            int active = 0;
            for (Employee employee : employees) {
                if (!employee.active) {
                    continue;
                }
                active++;
                for (String code = employee.slot.unit; code != null; code = parentOf(code)) {
                    headcount.merge(code, 1, Integer::sum);
                }
            }
            return new MonthEnd(lastMicrosecond, active, headcount);
        }
    }

    private static Instant instant(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
