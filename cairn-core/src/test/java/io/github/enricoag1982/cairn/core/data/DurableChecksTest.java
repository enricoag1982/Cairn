package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.fixtures.Derivations;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks in durable stores kept in a directory: the checks of a schema are recovered from the log and from a snapshot
 * file, compiled again with the language the store is opened with, and a store opened without one, or with one that
 * cannot compile them, refuses writes to the types they apply to (design D2 of {@code add-cel-expressions}).
 */
class DurableChecksTest {

    private static final SchemaDefinition MODEL = withCheck(withCheck(ReferenceModel.definition(), "Account",
            "balance-not-negative", "self.balance >= 0.0", "A balance cannot be negative."), "Account", "has-currency",
            "has(self.currency)", null);

    @TempDir
    Path directory;

    private DurableFixture durable;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private StoreSettings.Builder settings() {
        return StoreSettings.builder().clock(durable.clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules());
    }

    /** A store with the model, the parties and the accounts of RD-1 and RD-5; the IDs of A-1 and Alice come back. */
    private record Told(EntityId a1, EntityId a2, EntityId alice) {
    }

    private Told tell(DurableStore store) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), MODEL))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        Receipt accounts = durable.replay(store, ReferenceStories.rd5Accounts(entity(parties, BETA),
                entity(parties, ACME)));
        return new Told(entity(accounts, A1), entity(accounts, A2), entity(parties, ALICE));
    }

    private static Transaction setBalance(EntityId account, int balance) {
        return Transaction.by(PRINCIPAL).patch(account, from(4, 1), values("balance", balance)).build();
    }

    private void assertTheChecksHold(DurableStore store, Told told) {
        assertThat(store.schema().current().entityType("Account").orElseThrow().checks()).hasSize(2);
        CommitResult violated = store.commit(setBalance(told.a1(), -5));
        assertThat(violated).isInstanceOf(CommitResult.Rejected.class);
        Problem problem = ((CommitResult.Rejected) violated).problems().getFirst();
        assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_VIOLATED);
        assertThat(problem.message()).contains("balance-not-negative").contains("A balance cannot be negative.");
        assertThat(store.commit(setBalance(told.a1(), 0))).isInstanceOf(CommitResult.Committed.class);
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void theChecksAreRecoveredFromTheLog() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        DurableFixture.crash((DirectoryStore) first);

        DurableStore recovered = durable.openStore(settings().build());

        assertTheChecksHold(recovered, told);
    }

    @Test
    void theChecksAreRecoveredFromASnapshotFile() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();

        DurableStore reopened = durable.openStore(settings().build());

        assertTheChecksHold(reopened, told);
    }

    @Test
    void theChecksAreRecoveredFromALogThatFollowsASnapshotFile() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.writeSnapshot();
        assertThat(first.commit(setBalance(told.a2(), 7))).isInstanceOf(CommitResult.Committed.class);
        DurableFixture.crash((DirectoryStore) first);

        DurableStore recovered = durable.openStore(settings().build());

        assertTheChecksHold(recovered, told);
    }

    // ------------------------------------------------------------------ a store without a language

    @Test
    void aStoreOpenedWithoutALanguageRejectsEveryWriteToATypeWithChecksOncePerSubject() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();

        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());

        Transaction twoAccounts = Transaction.by(PRINCIPAL)
                .create(A1, "Account", from(1, 1), values("number", "A-9", "currency", "EUR", "balance", 1))
                .create(A2, "Account", from(1, 1), values("number", "A-8", "currency", "EUR", "balance", 1)).build();
        List<Problem> problems = ((CommitResult.Rejected) reopened.commit(twoAccounts)).problems().stream()
                .filter(problem -> problem.code() == ProblemCode.EXPRESSIONS_UNAVAILABLE).toList();
        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.EXPRESSIONS_UNAVAILABLE,
                ProblemCode.EXPRESSIONS_UNAVAILABLE);
        assertThat(problems).extracting(Problem::path).containsExactly("$a1", "$a2");
        assertThat(problems.getFirst().message()).contains("balance-not-negative").contains("has-currency")
                .contains("Account");
        assertThat(problems.getFirst().hint()).hasValueSatisfying(hint -> assertThat(hint).contains("cairn-cel"));

        assertThat(reopened.dryRun(setBalance(told.a1(), 5))).isInstanceOf(DryRunResult.Rejected.class);
        Transaction nothingChanges = Transaction.by(PRINCIPAL)
                .patch(told.a1(), from(1, 1), values("currency", "EUR")).build();
        assertThat(((CommitResult.Rejected) reopened.commit(nothingChanges)).problems())
                .as("whatever the write changes").singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE));
    }

    @Test
    void aStoreOpenedWithoutALanguageWritesTheOtherTypesAndReadsEverything() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();

        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());

        assertThat(reopened.commit(Transaction.by(PRINCIPAL)
                .patch(told.alice(), from(4, 1), values("name", "Alice Smith")).build()))
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(reopened.view(Coordinates.effectiveAt(ReferenceStories.on(2, 1))).entities("Account")).hasSize(2);
    }

    @Test
    void aStoreWithoutALanguageStillRegistersASchemaThatDeclaresNoChecksOfItsOwn() {
        DurableStore first = durable.openStore(settings().build());
        tell(first);
        first.close();
        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());
        SchemaDefinition other = SchemaDefinition.builder().entityType("Gadget", e -> e.attribute("name",
                ValueType.STRING)).build();

        RegistrationResult gadgets = reopened.schema().register(new Origin("gadgets", "1.0.0"), other);
        RegistrationResult again = reopened.schema().register(ReferenceModel.origin("1.1.0"), MODEL);

        assertThat(gadgets).as("the checks of another origin do not stop it")
                .isInstanceOf(RegistrationResult.Applied.class);
        assertThat(again).as("the origin that declares them cannot be registered").isInstanceOf(
                RegistrationResult.Rejected.class);
        assertThat(((RegistrationResult.Rejected) again).problems()).extracting(Problem::code)
                .containsOnly(ProblemCode.EXPRESSIONS_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ a language that cannot compile them

    @Test
    void aStoreOpenedWithALanguageThatCannotCompileACheckFailsTheWritesThatNeedIt() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();
        ExpressionLanguage newer = new ExpressionLanguage() {
            @Override
            public String name() {
                return "newer";
            }

            @Override
            public CompiledCheck compile(String expression, Map<String, ValueType> attributes)
                    throws ExpressionCompilationException {
                if (expression.contains("balance")) {
                    throw new ExpressionCompilationException("This version no longer reads balances.", 1, 1);
                }
                return values -> true;
            }

            @Override
            public CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes,
                    ValueType result) {
                return Derivations.of(values -> Optional.empty());
            }
        };

        DurableStore reopened = durable.openStore(settings().expressionLanguage(newer).build());

        List<Problem> problems = ((CommitResult.Rejected) reopened.commit(setBalance(told.a1(), 5))).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.CHECK_FAILED);
            assertThat(problem.message()).contains("balance-not-negative").contains("loaded")
                    .contains("This version no longer reads balances.");
        });
        assertThat(reopened.commit(Transaction.by(PRINCIPAL).patch(told.alice(), from(4, 1),
                values("name", "Alice Smith")).build())).isInstanceOf(CommitResult.Committed.class);
    }
}
