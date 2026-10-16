package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code derived-attributes} spec that the engine shows, one test each: derived attributes compiled
 * at registration, and computed at commit. The expressions are in CEL, so the tests need the module {@code cairn-cel} on
 * their class path, as they have it. The scenarios that commit run against RocksDB stores too (see {@link TestBackend}).
 * The scenarios about payloads and generated types are shown in the modules that have them.
 */
class DerivedAttributesScenariosTest {

    private static final String LABEL = "self.number + ' ' + self.currency";
    private static final String CENTS = "int(self.balance * 100.0)";
    private static final String BIRTH_YEAR = "has(self.birthDate) ? dyn(self.birthDate.getFullYear()) : null";
    private static final String BILLIONS = "int(self.balance) * 10000000";
    private static final String WORKING_DAYS_TO_2026 =
            "has(self.birthDate) ? dyn(cairn.workingDays(self.birthDate, timestamp('2025-12-31T00:00:00Z'))) : null";
    private static final String CALENDAR_DAYS_TO_2026 =
            "has(self.birthDate) ? dyn(cairn.calendarDays(self.birthDate, timestamp('2025-12-31T00:00:00Z'))) : null";
    private static final TemporaryId BOB = new TemporaryId("bob");

    /** The reference model, in which Account declares the test-only derived attribute {@code label}. */
    private static SchemaDefinition accountWithLabel() {
        return withDerivedAttribute(ReferenceModel.definition(), "Account", "label", ValueType.STRING, false, LABEL);
    }

    private static SchemaRegistry registryWithLanguage() {
        return SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(),
                Optional.of(ExpressionLanguages.discovered().orElseThrow()));
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static Value valueOf(EntityState state, String attribute) {
        return state.values().get(attribute);
    }

    private static ReferenceStore storeWith(SchemaDefinition definition) {
        return ReferenceStore.with(definition);
    }

    /**
     * A store of the reference model in which Person declares a test-only derived attribute, and in which a person born
     * on a date is created.
     */
    private static EntityState personBornOn(String attribute, String expression, LocalDate birthDate) {
        ReferenceStore reference = storeWith(withDerivedAttribute(ReferenceModel.definition(), "Person", attribute,
                ValueType.INT, false, expression));
        Receipt receipt = reference.commit(Transaction.by(PRINCIPAL)
                .create(BOB, "Person", from(1, 1), values("name", "Bob", "birthDate", birthDate)).build());
        return reference.view(on(3, 31)).entity(entity(receipt, BOB)).orElseThrow();
    }

    // ------------------------------------------------------------------ compiled at registration

    @Test
    @DisplayName("Scenario: A derived label")
    void aDerivedLabel() {
        SchemaRegistry registry = registryWithLanguage();
        SchemaDefinition definition = accountWithLabel();

        assertThat(registry.validate(ReferenceModel.origin("1.0.0"), definition)).isEmpty();

        Schema schema = applied(registry.register(ReferenceModel.origin("1.0.0"), definition)).schema();
        Attribute label = schema.entityType("Account").orElseThrow().attributes().stream()
                .filter(attribute -> attribute.name().equals("label")).findFirst().orElseThrow();
        assertThat(label.derived()).hasValue(LABEL);
        assertThat(schema.entityType("Account").orElseThrow().attributes().stream()
                .filter(attribute -> attribute.derived().isPresent())).extracting(Attribute::name)
                .containsExactly("label");
    }

    @Test
    @DisplayName("Scenario: A derivation that reads a derived attribute")
    void aDerivationThatReadsADerivedAttribute() {
        SchemaDefinition definition = withDerivedAttribute(accountWithLabel(), "Account", "shout", ValueType.STRING,
                false, "self.label + '!'");

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Account.shout");
        assertThat(problem.message()).contains("label");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("label")
                .contains("not derived"));
    }

    @Test
    @DisplayName("Scenario: A derivation of the wrong type")
    void aDerivationOfTheWrongType() {
        SchemaDefinition definition = withDerivedAttribute(ReferenceModel.definition(), "Account", "cents",
                ValueType.INT, false, "self.balance * 100.0");

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_DERIVATION);
        assertThat(problem.path()).isEqualTo("Account.cents");
        assertThat(problem.message()).contains("int").contains("double");
    }

    // ------------------------------------------------------------------ computed at commit

    @Test
    @DisplayName("Scenario: Derived on creation")
    void derivedOnCreation() {
        ReferenceStore reference = storeWith(accountWithLabel());
        Receipt accounts = reference.createAccounts();

        EntityState a1 = reference.view(on(3, 31)).entity(entity(accounts, A1)).orElseThrow();

        assertThat(valueOf(a1, "label")).isEqualTo(Value.of("A-1 EUR"));
        assertThat(valueOf(a1, "number")).isEqualTo(Value.of("A-1"));
    }

    @Test
    @DisplayName("Scenario: Derived again for every written segment")
    void derivedAgainForEveryWrittenSegment() {
        ReferenceStore reference = storeWith(withDerivedAttribute(ReferenceModel.definition(), "Account",
                "balanceInCents", ValueType.LONG, false, CENTS));
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        reference.replay(ReferenceStories.rd5BalanceCorrection(a1));

        EntityState march31 = reference.view(on(3, 31)).entity(a1).orElseThrow();
        EntityState march31KnownApril16 = reference.store
                .view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 16))).entity(a1).orElseThrow();
        EntityState january31 = reference.view(on(1, 31)).entity(a1).orElseThrow();

        assertThat(valueOf(march31, "balanceInCents")).as("effective 31 March, as known now")
                .isEqualTo(Value.of(120_000L));
        assertThat(valueOf(march31KnownApril16, "balanceInCents")).as("effective 31 March, as known on 16 April")
                .isEqualTo(Value.of(150_000L));
        assertThat(valueOf(january31, "balanceInCents")).as("effective 31 January").isEqualTo(Value.of(100_000L));
    }

    @Test
    @DisplayName("Scenario: An optional derived attribute without a value")
    void anOptionalDerivedAttributeWithoutAValue() {
        ReferenceStore reference = storeWith(withDerivedAttribute(ReferenceModel.definition(), "Person", "birthYear",
                ValueType.INT, false, BIRTH_YEAR));

        Receipt receipt = reference.commit(Transaction.by(PRINCIPAL)
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .create(BOB, "Person", from(1, 1), values("name", "Bob", "birthDate", LocalDate.of(1990, 5, 1)))
                .build());

        EntityState alice = reference.view(on(3, 31)).entity(entity(receipt, ALICE)).orElseThrow();
        EntityState bob = reference.view(on(3, 31)).entity(entity(receipt, BOB)).orElseThrow();
        assertThat(alice.values()).as("Alice has no birth date, so no birth year").doesNotContainKey("birthYear");
        assertThat(valueOf(bob, "birthYear")).isEqualTo(Value.of(1990));
    }

    @Test
    @DisplayName("Scenario: A derived value that does not fit")
    void aDerivedValueThatDoesNotFit() {
        ReferenceStore reference = storeWith(withDerivedAttribute(ReferenceModel.definition(), "Account",
                "balanceInBillions", ValueType.INT, false, BILLIONS));
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-1", "currency", "EUR", "balance", money("1000")))
                .relate(HELD_BY_A1, "HeldBy", A1, parties.temporaryIds().get(BETA), from(1, 1), values())
                .build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DERIVATION_FAILED);
        assertThat(problem.path()).isEqualTo("$a1.balanceInBillions");
        assertThat(problem.message()).contains("balanceInBillions").contains("10000000000");
    }

    @Test
    @DisplayName("Scenario: A query on a derived value")
    void aQueryOnADerivedValue() {
        ReferenceStore reference = storeWith(accountWithLabel());
        reference.createAccounts();
        Query query = Query.from("Account", "a")
                .where(Condition.eq(Operand.attribute("a", "label"), Operand.literal("A-2 EUR")))
                .build();

        QueryResult.Rows result = rows(reference.view(on(3, 31)).query(query));

        assertThat(result.rows()).singleElement().satisfies(row ->
                assertThat(QueryRows.text(row.entity("a").orElseThrow(), "number")).isEqualTo("A-2"));
    }

    // ------------------------------------------------------------------ functions of Cairn's own

    @Test
    @DisplayName("Scenario: Working days across a weekend")
    void workingDaysAcrossAWeekend() {
        LocalDate friday = LocalDate.of(2025, 12, 26);

        EntityState bob = personBornOn("workingDaysTo2026", WORKING_DAYS_TO_2026, friday);

        assertThat(friday.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(valueOf(bob, "workingDaysTo2026")).as("26, 29, 30 and 31 December").isEqualTo(Value.of(4));
    }

    @Test
    @DisplayName("Scenario: Calendar days, both included")
    void calendarDaysBothIncluded() {
        EntityState bob = personBornOn("calendarDaysTo2026", CALENDAR_DAYS_TO_2026, LocalDate.of(2025, 12, 26));

        assertThat(valueOf(bob, "calendarDaysTo2026")).isEqualTo(Value.of(6));
    }

    @Test
    @DisplayName("Scenario: A last day before the first")
    void aLastDayBeforeTheFirst() {
        EntityState bob = personBornOn("workingDaysTo2026", WORKING_DAYS_TO_2026, LocalDate.of(2026, 1, 2));

        assertThat(valueOf(bob, "workingDaysTo2026")).isEqualTo(Value.of(0));
    }
}
