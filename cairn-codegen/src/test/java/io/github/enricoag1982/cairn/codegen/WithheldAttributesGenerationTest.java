package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.document;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generated types and the attributes that a state withheld (design D6 of {@code add-attribute-access-control}, ADR-0036):
 * a type is built from such a state also when the attribute is required, and the accessor of the attribute fails with
 * an {@link IllegalStateException} that names it and says that it was withheld, instead of reporting a value that does
 * not exist.
 */
class WithheldAttributesGenerationTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final EntityId ID = new EntityId("00000000-0000-7000-8000-000000000001");
    private static final RelationshipId LINK = new RelationshipId("00000000-0000-7000-8000-0000000000a1");

    /** Required classified attributes of every value type, an optional one, and one that is not classified. */
    private static final String EMPLOYEE = """
            cairn: 1
            origin: { name: staff, version: "1.0.0" }
            entityTypes:
              Employee:
                attributes:
                  name: { type: string, required: true }
                  salary: { type: int, required: true, classification: pay }
                  grade: { type: string, required: true, classification: pay }
                  insured: { type: boolean, required: true, classification: pay }
                  points: { type: long, required: true, classification: pay }
                  rate: { type: decimal, required: true, classification: pay }
                  hired: { type: date, required: true, classification: pay }
                  reviewed: { type: instant, required: true, classification: pay }
                  bonus: { type: decimal, classification: pay }
                  nickname: { type: string }
            """;

    private static final List<String> CLASSIFIED = List.of("salary", "grade", "insured", "points", "rate", "hired",
            "reviewed", "bonus");

    @TempDir
    Path directory;

    /** The source with the lines of a Javadoc comment joined, so that a sentence the generator wrapped is in one piece. */
    private static String flat(String source) {
        return source.replaceAll("\\s*\n\\s*\\*\\s+", " ");
    }

    private static Object call(Class<?> type, String name, Object argument) throws Exception {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                return method.invoke(null, argument);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object get(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }

    /** The exception that an accessor throws. */
    private static Throwable failure(Object target, String accessor) {
        return catchThrowableOfType(InvocationTargetException.class, () -> get(target, accessor)).getCause();
    }

    private static <T extends Throwable> T catchThrowableOfType(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            assertThat(thrown).isInstanceOf(type);
            return type.cast(thrown);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + ", but nothing was thrown");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static EntityState employee(Map<String, Value> values, Set<String> withheld) {
        return new EntityState(ID, "Employee", values, EffectivePeriod.from(FROM), FROM, new Basis(1), withheld);
    }

    private static Map<String, Value> everyValue() {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("name", Value.of("Ana"));
        values.put("salary", Value.of(3000));
        values.put("grade", Value.of("B2"));
        values.put("insured", Value.of(true));
        values.put("points", Value.of(12L));
        values.put("rate", Value.of(new BigDecimal("12.50")));
        values.put("hired", Value.of(LocalDate.of(2020, 3, 1)));
        values.put("reviewed", Value.of(FROM));
        values.put("bonus", Value.of(new BigDecimal("500")));
        values.put("nickname", Value.of("A"));
        return values;
    }

    @Test
    @DisplayName("Scenario: Reading a withheld balance")
    void readingAWithheldBalance() throws Exception {
        String classified = Files.readString(ReferenceDomain.DOCUMENT).replace(
                "balance: { type: decimal, required: true }",
                "balance: { type: decimal, required: true, classification: finance }");
        assertThat(classified).contains("classification: finance");
        Path file = document(directory, "reference.cairn.yaml", classified);
        Generation.Generated generated = generate(file);
        ReferenceDomain domain = new ReferenceDomain(file, ReferenceModel.executableRules());
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        Receipt accounts = domain.replay(ReferenceStories.rd5Accounts(parties.temporaryIds().get(ReferenceStories.BETA),
                parties.temporaryIds().get(ReferenceStories.ACME)));
        EntityId a1 = ReferenceDomain.entityId(accounts, ReferenceStories.A1);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> account = loader.loadClass(PACKAGE + ".Account");
            EntityState state = domain.view(ReferenceStories.on(4, 1)).restrictedTo(Clearance.none()).entity(a1)
                    .orElseThrow();

            Object read = call(account, "from", state);

            assertThat(state.withheld()).containsExactly("balance");
            assertThat(get(read, "number")).isEqualTo("A-1");
            assertThat(failure(read, "balance")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("balance").hasMessageContaining("withheld");
            EntityState everything = domain.view(ReferenceStories.on(4, 1)).entity(a1).orElseThrow();
            assertThat(get(call(account, "from", everything), "balance")).isEqualTo(new BigDecimal("1000"));
        }
    }

    @Test
    void aStateThatWithheldRequiredAttributesOfEveryValueTypeStillReadsAsTheGeneratedType() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Map<String, Value> values = new LinkedHashMap<>();
            values.put("name", Value.of("Ana"));
            EntityState state = employee(values, Set.copyOf(CLASSIFIED));

            Object read = call(employee, "from", state);

            assertThat(get(read, "name")).isEqualTo("Ana");
            assertThat(get(read, "nickname")).isEqualTo(Optional.empty());
            assertThat(get(read, "entityState")).isSameAs(state);
            assertThat(((EntityState) get(read, "entityState")).withheld()).containsExactlyInAnyOrderElementsOf(CLASSIFIED);
        }
    }

    @Test
    void theAccessorOfAWithheldAttributeFailsNamingTheAttributeAndSayingThatItWasWithheld() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Object read = call(employee, "from", employee(Map.of("name", Value.of("Ana")), Set.copyOf(CLASSIFIED)));

            for (String attribute : CLASSIFIED) {
                assertThat(failure(read, attribute)).as(attribute).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("attribute " + attribute + " ").hasMessageContaining("was withheld")
                        .hasMessageContaining(ID.value());
            }
        }
    }

    @Test
    void anOptionalAttributeThatWasWithheldIsNotReportedAsEmpty() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Map<String, Value> values = everyValue();
            values.keySet().removeAll(List.of("bonus"));
            Object read = call(employee, "from", employee(values, Set.of("bonus")));

            assertThat(failure(read, "bonus")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bonus").hasMessageContaining("withheld");
            assertThat(get(read, "salary")).isEqualTo(3000);
        }
    }

    @Test
    void aStateThatWithheldNothingReadsEveryAttributeAsBefore() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");

            Object read = call(employee, "from", employee(everyValue(), Set.of()));

            assertThat(get(read, "salary")).isEqualTo(3000);
            assertThat(get(read, "grade")).isEqualTo("B2");
            assertThat(get(read, "insured")).isEqualTo(true);
            assertThat(get(read, "points")).isEqualTo(12L);
            assertThat(get(read, "rate")).isEqualTo(new BigDecimal("12.50"));
            assertThat(get(read, "hired")).isEqualTo(LocalDate.of(2020, 3, 1));
            assertThat(get(read, "reviewed")).isEqualTo(FROM);
            assertThat(get(read, "bonus")).isEqualTo(Optional.of(new BigDecimal("500")));
        }
    }

    @Test
    void aStateThatWithheldSomeAttributesReadsTheOthers() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Map<String, Value> values = everyValue();
            values.remove("salary");

            Object read = call(employee, "from", employee(values, Set.of("salary")));

            assertThat(failure(read, "salary")).hasMessageContaining("salary");
            assertThat(get(read, "grade")).isEqualTo("B2");
            assertThat(get(read, "bonus")).isEqualTo(Optional.of(new BigDecimal("500")));
        }
    }

    @Test
    void aMissingRequiredValueThatTheStateDidNotWithholdIsStillAnError() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Map<String, Value> values = everyValue();
            values.remove("salary");

            Throwable thrown = catchThrowableOfType(InvocationTargetException.class,
                    () -> call(employee, "from", employee(values, Set.of()))).getCause();

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("salary");
        }
    }

    @Test
    void theAccessorsOfClassifiedAttributesSayWhatAWithheldValueDoes() throws Exception {
        Generation.Generated generated = generate(directory, EMPLOYEE);

        String source = flat(text(generated, "Employee"));

        assertThat(source.split("Classified <code>pay</code>: a state read through a view that is restricted to a "
                + "clearance that does not cover <code>pay</code> withholds this attribute, and the accessor then "
                + "throws an <code>IllegalStateException</code> that names the attribute and says that it was "
                + "withheld", -1)).as("one paragraph for each of the eight classified attributes").hasSize(9);
        assertThat(source.split("@throws IllegalStateException if the state withheld the attribute", -1))
                .as("one tag for each of them").hasSize(9);
        compile(generated, directory);
    }

    @Test
    void aTypeWithoutClassifiedAttributesIsGeneratedAsBefore() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: plain, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      name: { type: string, required: true }
                      size: { type: int }
                """);

        String source = text(generated, "Thing");

        assertThat(source).doesNotContain("requireHeld").doesNotContain("withheld").doesNotContain("Classified")
                .doesNotContain("IllegalStateException").doesNotContain("@Override");
        compile(generated, directory);
    }

    @Test
    void anInheritedClassifiedAttributeFailsInTheSnapshotsOfSubtypesAndOfTraits() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: staff, version: "1.0.0" }
                traits:
                  Paid:
                    attributes:
                      salary: { type: int, required: true, classification: pay }
                entityTypes:
                  Person:
                    abstract: true
                    attributes:
                      name: { type: string, required: true }
                      born: { type: date, classification: identity }
                  Employee:
                    supertype: Person
                    traits: [Paid]
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> person = loader.loadClass(PACKAGE + ".Person");
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            Class<?> paid = loader.loadClass(PACKAGE + ".Paid");
            EntityState state = employee(Map.of("name", Value.of("Ana")), Set.of("salary", "born"));

            Object asEmployee = call(employee, "from", state);
            Object asPerson = call(person, "from", state);

            assertThat(asEmployee).isInstanceOf(paid).isInstanceOf(person);
            assertThat(failure(asEmployee, "salary")).hasMessageContaining("salary").hasMessageContaining("withheld");
            assertThat(failure(asEmployee, "born")).hasMessageContaining("born").hasMessageContaining("withheld");
            assertThat(failure(asPerson, "born")).hasMessageContaining("born");
            assertThat(get(asEmployee, "name")).isEqualTo("Ana");
            assertThat(text(generated, "Paid")).contains("Classified <code>pay</code>");
            assertThat(text(generated, "Person")).contains("Classified <code>identity</code>");
        }
    }

    @Test
    void aClassifiedAttributeOfARelationshipTypeFailsInTheSameWay() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: staff, version: "1.0.0" }
                entityTypes:
                  Employee: {}
                  Department: {}
                relationshipTypes:
                  WorksIn:
                    source: Employee
                    target: Department
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: employs
                    attributes:
                      role: { type: string, required: true }
                      allowance: { type: decimal, required: true, classification: pay }
                      note: { type: string, classification: pay }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> worksIn = loader.loadClass(PACKAGE + ".WorksIn");
            RelationshipState state = new RelationshipState(LINK, "WorksIn", ID,
                    new EntityId("00000000-0000-7000-8000-000000000002"), Map.of("role", Value.of("lead")),
                    EffectivePeriod.from(FROM), FROM, new Basis(1), Set.of("allowance", "note"));

            Object read = call(worksIn, "from", state);

            assertThat(get(read, "role")).isEqualTo("lead");
            assertThat(failure(read, "allowance")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("allowance").hasMessageContaining("withheld")
                    .hasMessageContaining("relationship").hasMessageContaining(LINK.value());
            assertThat(failure(read, "note")).hasMessageContaining("note");
            assertThat(flat(text(generated, "WorksIn"))).contains("Classified <code>pay</code>")
                    .contains("the state does not say whether the relationship has a value");
        }
    }

    @Test
    void aDeprecatedClassifiedAttributeCompilesWithoutWarnings() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: staff, version: "1.0.0" }
                entityTypes:
                  Employee:
                    attributes:
                      salary: { type: int, required: true, classification: pay, deprecated: true }
                """);

        compile(generated, directory);
    }

    @Test
    void aTypeNamedLikeALibraryTypeStillCompiles() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: odd, version: "1.0.0" }
                entityTypes:
                  Override: {}
                  IllegalStateException: {}
                  TypedValues: {}
                  Employee:
                    attributes:
                      salary: { type: int, required: true, classification: pay }
                """);

        compile(generated, directory);
    }

    /** The whole way: a store whose view is restricted to a clearance, and the type read from what it returns. */
    @Test
    void aStateReadThroughARestrictedViewReadsAsTheGeneratedTypeAndItsWithheldAccessorsFail() throws Exception {
        Path file = document(directory, "staff.cairn.yaml", EMPLOYEE);
        Generation.Generated generated = generate(file);
        TemporaryId ana = new TemporaryId("ana");
        ReferenceDomain domain = new ReferenceDomain(file, List.of());
        Receipt hired = domain.commit(ReferenceStories.on(1, 1), Transaction.by(ReferenceStories.PRINCIPAL)
                .create(ana, "Employee", ReferenceStories.from(1, 1), ReferenceStories.values("name", "Ana", "salary",
                        3000, "grade", "B2", "insured", Value.of(true), "points", 12L, "rate",
                        new BigDecimal("12.5"), "hired", LocalDate.of(2020, 3, 1), "reviewed", Value.of(FROM),
                        "bonus", new BigDecimal("500")))
                .build());
        EntityId id = ReferenceDomain.entityId(hired, ana);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> employee = loader.loadClass(PACKAGE + ".Employee");
            View open = domain.view(ReferenceStories.on(2, 1));
            EntityState unrestricted = open.entity(id).orElseThrow();
            EntityState restricted = open.restrictedTo(Clearance.none()).entity(id).orElseThrow();
            EntityState covered = open.restrictedTo(Clearance.of("pay")).entity(id).orElseThrow();

            Object everything = call(employee, "from", unrestricted);
            Object withheld = call(employee, "from", restricted);
            Object cleared = call(employee, "from", covered);

            assertThat(get(everything, "salary")).isEqualTo(3000);
            assertThat(get(cleared, "salary")).isEqualTo(3000);
            assertThat(get(withheld, "name")).isEqualTo("Ana");
            for (String attribute : CLASSIFIED) {
                assertThat(failure(withheld, attribute)).as(attribute).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(attribute).hasMessageContaining("withheld");
            }
            assertThatThrownBy(() -> get(withheld, "salary")).hasCauseInstanceOf(IllegalStateException.class);
        }
    }
}
