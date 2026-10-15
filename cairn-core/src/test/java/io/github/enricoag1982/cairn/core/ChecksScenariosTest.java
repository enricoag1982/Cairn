package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.Results.applied;
import static io.github.enricoag1982.cairn.core.Results.rejected;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EffectiveCheck;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the {@code checks} spec, one test each: checks on types, compiled at registration, and run at commit.
 * The checks are in CEL, so the tests need the module {@code cairn-cel} on their class path, as they have it. The
 * scenarios that commit run against RocksDB stores too (see {@link TestBackend}).
 */
class ChecksScenariosTest {

    private static final String NON_NEGATIVE = "self.balance >= 0.0";
    private static final String BORN_BEFORE_2026 = "self.birthDate < timestamp('2026-01-01T00:00:00Z')";
    private static final String BORN_BEFORE_2026_IF_KNOWN = "!has(self.birthDate) || " + BORN_BEFORE_2026;

    @TempDir
    Path directory;

    private final List<DurableStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        opened.forEach(DurableStore::close);
    }

    /** The reference model, in which Account declares the test-only check {@code balance-not-negative}. */
    private static SchemaDefinition accountChecked(String message) {
        return withCheck(ReferenceModel.definition(), "Account", "balance-not-negative", NON_NEGATIVE, message);
    }

    private static SchemaRegistry registryWithLanguage() {
        return SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(),
                Optional.of(ExpressionLanguages.discovered().orElseThrow()));
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static ReferenceStore storeWith(SchemaDefinition definition) {
        return ReferenceStore.withRules(definition, ReferenceModel.executableRules(), ReferenceModel.declaredRules());
    }

    // ------------------------------------------------------------------ checks on types

    @Test
    @DisplayName("Scenario: A check on Account")
    void aCheckOnAccount() {
        SchemaRegistry registry = registryWithLanguage();
        SchemaDefinition definition = accountChecked(null);

        assertThat(registry.validate(ReferenceModel.origin("1.0.0"), definition)).isEmpty();

        Schema schema = applied(registry.register(ReferenceModel.origin("1.0.0"), definition)).schema();
        Check expected = new Check("balance-not-negative", NON_NEGATIVE);
        assertThat(schema.entityType("Account").orElseThrow().checks()).containsExactly(expected);
        assertThat(schema.entityType("Account").orElseThrow().effectiveChecks())
                .containsExactly(new EffectiveCheck(expected, "Account"));
    }

    @Test
    @DisplayName("Scenario: A check inherited from a supertype")
    void aCheckInheritedFromASupertype() {
        SchemaRegistry registry = registryWithLanguage();
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Party", "name-not-empty",
                "size(self.name) > 0", null);

        Schema schema = applied(registry.register(ReferenceModel.origin("1.0.0"), definition)).schema();

        Check inherited = new Check("name-not-empty", "size(self.name) > 0");
        assertThat(schema.entityType("Organization").orElseThrow().effectiveChecks())
                .containsExactly(new EffectiveCheck(inherited, "Party"));
        assertThat(schema.entityType("Organization").orElseThrow().checks()).as("declared by Organization").isEmpty();
        assertThat(schema.entityType("Party").orElseThrow().checks()).containsExactly(inherited);
    }

    @Test
    @DisplayName("Scenario: A check declared twice in a hierarchy")
    void aCheckDeclaredTwiceInAHierarchy() {
        SchemaDefinition definition = withCheck(withCheck(ReferenceModel.definition(), "Party", "name-not-empty",
                "size(self.name) > 0", null), "Organization", "name-not-empty", "size(self.name) > 0", null);

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertThat(problem.path()).isEqualTo("Organization.name-not-empty");
    }

    @Test
    @DisplayName("Scenario: A check named like an attribute")
    void aCheckNamedLikeAnAttribute() {
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Account", "balance", NON_NEGATIVE, null);

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.DUPLICATE_CHECK);
        assertThat(problem.path()).isEqualTo("Account.balance");
        assertThat(problem.message()).contains("taken by an attribute").contains("balance");
    }

    @Test
    @DisplayName("Scenario: An invalid check name")
    void anInvalidCheckName() {
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Account", "Balance_Check", NON_NEGATIVE,
                null);

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK_NAME);
        assertThat(problem.path()).isEqualTo("Account.Balance_Check");
    }

    // ------------------------------------------------------------------ compiled at registration

    @Test
    @DisplayName("Scenario: An expression that does not compile")
    void anExpressionThatDoesNotCompile() {
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                "self.balance >=", null);

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(problem.path()).isEqualTo("Account.balance-not-negative");
        assertThat(problem.message()).as("the position in the expression").containsPattern("at line 1, column \\d+");
    }

    @Test
    @DisplayName("Scenario: An unknown attribute in a check")
    void anUnknownAttributeInACheck() {
        SchemaDefinition definition = withCheck(ReferenceModel.definition(), "Account", "amount-not-negative",
                "self.amount >= 0.0", null);

        List<Problem> problems = registryWithLanguage().validate(ReferenceModel.origin("1.0.0"), definition);

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
        assertThat(problem.path()).isEqualTo("Account.amount-not-negative");
        assertThat(problem.message()).contains("amount");
    }

    @Test
    @DisplayName("Scenario: No expression language")
    void noExpressionLanguage() {
        SchemaRegistry registry = SchemaRegistries.inMemory(new TestClock(), ReferenceModel.rules(), Optional.empty());

        List<Problem> problems = rejected(registry.register(ReferenceModel.origin("1.0.0"), accountChecked(null)));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECKS_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo("Account.balance-not-negative");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("cairn-cel"));
        assertThat(registry.current().isEmpty()).as("nothing is registered").isTrue();
    }

    // ------------------------------------------------------------------ run at commit

    @Test
    @DisplayName("Scenario: A violated check")
    void aViolatedCheck() {
        ReferenceStore reference = storeWith(accountChecked("A balance cannot be negative."));
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("balance", -5)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(problem.path()).isEqualTo(a1.value());
        assertThat(problem.message()).contains("balance-not-negative").contains("A balance cannot be negative.");
    }

    @Test
    @DisplayName("Scenario: A check that holds")
    void aCheckThatHolds() {
        ReferenceStore reference = storeWith(accountChecked("A balance cannot be negative."));
        EntityId a1 = ReferenceStore.entity(reference.createAccounts(), A1);

        Receipt receipt = reference.commit(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("balance", 0)).build());

        assertThat(receipt.basis().value()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Scenario: An attribute without a value")
    void anAttributeWithoutAValue() {
        Transaction alice = Transaction.by(PRINCIPAL).create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .build();
        ReferenceStore unguarded = storeWith(withCheck(ReferenceModel.definition(), "Person", "born-before-2026",
                BORN_BEFORE_2026, null));

        Problem failed = only(unguarded.reject(alice));

        assertThat(failed.code()).isEqualTo(ProblemCode.CHECK_FAILED);
        assertThat(failed.message()).contains("born-before-2026");

        ReferenceStore guarded = storeWith(withCheck(ReferenceModel.definition(), "Person", "born-before-2026",
                BORN_BEFORE_2026_IF_KNOWN, null));

        assertThat(guarded.store.commit(alice)).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    @DisplayName("Scenario: A check on a relationship")
    void aCheckOnARelationship() {
        ReferenceStore reference = storeWith(withCheck(ReferenceModel.definition(), "Employs", "title-not-blank",
                "!has(self.title) || size(self.title) > 0", null));
        Receipt parties = reference.createParties();

        List<Problem> problems = reference.reject(Transaction.by(PRINCIPAL)
                .relate(BETA_EMPLOYS_ALICE, "Employs", parties.temporaryIds().get(BETA),
                        parties.temporaryIds().get(ALICE), from(3, 1), values("title", "")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(problem.message()).contains("title-not-blank");
    }

    @Test
    @DisplayName("Scenario: A store opened without an expression language")
    void aStoreOpenedWithoutAnExpressionLanguage() {
        TestClock clock = new TestClock();
        DurableStore first = open(settings(clock).build());
        assertThat(first.schema().register(ReferenceModel.origin("1.0.0"), accountChecked(null)))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = ReferenceStories.replay(first, clock, ReferenceStories.rd1Parties());
        Receipt accounts = ReferenceStories.replay(first, clock, ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
        EntityId a1 = (EntityId) accounts.temporaryIds().get(A1);
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        first.close();

        DurableStore reopened = open(settings(clock).noExpressionLanguage().build());
        clock.moveTo(on(4, 2));

        CommitResult setsBalance = reopened.commit(Transaction.by(PRINCIPAL)
                .patch(a1, from(4, 1), values("balance", 10)).build());
        CommitResult renames = reopened.commit(Transaction.by(PRINCIPAL)
                .patch(alice, from(4, 1), values("name", "Alice Smith")).build());

        assertThat(setsBalance).isInstanceOf(CommitResult.Rejected.class);
        Problem problem = only(((CommitResult.Rejected) setsBalance).problems());
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECKS_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo(a1.value());
        assertThat(renames).isInstanceOf(CommitResult.Committed.class);
        assertThat(reopened.schema().current().entityType("Account").orElseThrow().checks())
                .as("the schema keeps its checks").hasSize(1);
    }

    private StoreSettings.Builder settings(TestClock clock) {
        return StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules());
    }

    /** Opens a durable store of the backend under test on the test's directory, which {@link #closeStores} closes. */
    private DurableStore open(StoreSettings settings) {
        DurableStore store = TestBackend.isRocksDb() ? TestBackend.openRocksDb(directory, settings)
                : Stores.durable(directory, settings);
        opened.add(store);
        return store;
    }
}
