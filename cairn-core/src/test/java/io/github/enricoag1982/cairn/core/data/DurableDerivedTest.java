package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.StubLanguage;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.CompiledDerivation;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Derived attributes in durable stores kept in a directory: the derived values of a version are stored with it and are
 * recovered from the log and from a snapshot file as they were stored, without being computed again; the derivations of
 * the schema are recovered with it and compiled again with the language the store is opened with, and a store opened
 * without one, or with one that cannot compile them, refuses writes to the types that have them (design D4 of
 * {@code add-derived-attributes}).
 */
class DurableDerivedTest {

    private static final SchemaDefinition MODEL = withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
            ValueType.STRING, true, "self.number + ' ' + self.currency");

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

    private record Told(EntityId a1, EntityId alice) {
    }

    private Told tell(DurableStore store) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), MODEL))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        Receipt accounts = durable.replay(store, ReferenceStories.rd5Accounts(entity(parties, BETA),
                entity(parties, ACME)));
        return new Told(entity(accounts, A1), entity(parties, ALICE));
    }

    private static Transaction setCurrency(EntityId account, String currency) {
        return Transaction.by(PRINCIPAL).patch(account, from(4, 1), values("currency", currency)).build();
    }

    private static Value label(DurableStore store, EntityId account, int month, int day) {
        return store.view(Coordinates.effectiveAt(on(month, day))).entity(account).orElseThrow().values().get("label");
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void theDerivedValuesAndTheirExpressionsAreRecoveredFromTheLogWithoutComputingAgain() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        DurableFixture.crash((DirectoryStore) first);
        StubLanguage language = new StubLanguage();

        DurableStore recovered = durable.openStore(settings().expressionLanguage(language).build());

        assertThat(label(recovered, told.a1(), 3, 31)).as("as it was stored").isEqualTo(Value.of("A-1 EUR"));
        assertThat(language.evaluations()).as("recovery computes nothing").isZero();
        assertThat(language.derivations()).as("the recorded expression is compiled again, for Account")
                .singleElement().satisfies(compiled -> {
                    assertThat(compiled.expression()).isEqualTo("self.number + ' ' + self.currency");
                    assertThat(compiled.attributes()).containsOnlyKeys("number", "currency", "balance");
                    assertThat(compiled.result()).isEqualTo(ValueType.STRING);
                });
        assertThat(recovered.schema().current().entityType("Account").orElseThrow().attributes())
                .anyMatch(attribute -> attribute.name().equals("label") && attribute.derived().isPresent()
                        && attribute.required());
    }

    @Test
    void theDerivedValuesAreRecoveredFromASnapshotFile() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();
        StubLanguage language = new StubLanguage();

        DurableStore reopened = durable.openStore(settings().expressionLanguage(language).build());

        assertThat(label(reopened, told.a1(), 3, 31)).isEqualTo(Value.of("A-1 EUR"));
        assertThat(language.evaluations()).isZero();
    }

    @Test
    void theDerivedValuesAreRecoveredFromALogThatFollowsASnapshotFile() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.writeSnapshot();
        assertThat(first.commit(setCurrency(told.a1(), "USD"))).isInstanceOf(CommitResult.Committed.class);
        DurableFixture.crash((DirectoryStore) first);

        DurableStore recovered = durable.openStore(settings().build());

        assertThat(label(recovered, told.a1(), 3, 31)).isEqualTo(Value.of("A-1 EUR"));
        assertThat(label(recovered, told.a1(), 4, 30)).isEqualTo(Value.of("A-1 USD"));
        assertThat(recovered.commit(setCurrency(told.a1(), "GBP"))).as("and the derivation goes on")
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(label(recovered, told.a1(), 4, 30)).isEqualTo(Value.of("A-1 GBP"));
    }

    @Test
    void aStoreReopenedWithALanguageComputesTheNextWriteWithIt() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();
        StubLanguage language = new StubLanguage();
        DurableStore reopened = durable.openStore(settings().expressionLanguage(language).build());

        assertThat(reopened.commit(setCurrency(told.a1(), "USD"))).isInstanceOf(CommitResult.Committed.class);

        assertThat(language.evaluations()).isEqualTo(1);
        assertThat(label(reopened, told.a1(), 4, 30)).isEqualTo(Value.of("stub"));
    }

    // ------------------------------------------------------------------ a store without a language

    @Test
    void aStoreOpenedWithoutALanguageRejectsEveryWriteToATypeWithDerivedAttributesOncePerSubject() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();

        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());

        List<Problem> problems = ((CommitResult.Rejected) reopened.commit(setCurrency(told.a1(), "USD"))).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
            assertThat(problem.path()).isEqualTo(told.a1().value());
            assertThat(problem.message()).contains("label").contains("Account").contains("derived");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("cairn-cel"));
        });
        assertThat(reopened.dryRun(setCurrency(told.a1(), "USD"))).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((CommitResult.Rejected) reopened.commit(Transaction.by(PRINCIPAL)
                .endDate(told.a1(), on(9, 1)).build())).problems()).as("whatever the write changes")
                .extracting(Problem::code).contains(ProblemCode.EXPRESSIONS_UNAVAILABLE);
    }

    @Test
    void aStoreOpenedWithoutALanguageWritesTheOtherTypesAndReadsTheStoredDerivedValues() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();

        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());

        assertThat(reopened.commit(Transaction.by(PRINCIPAL)
                .patch(told.alice(), from(4, 1), values("name", "Alice Smith")).build()))
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(label(reopened, told.a1(), 3, 31)).isEqualTo(Value.of("A-1 EUR"));
        EntityState a1 = reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(told.a1()).orElseThrow();
        assertThat(a1.values()).containsKey("label");
    }

    @Test
    void aStoreWithoutALanguageStillRegistersASchemaThatDeclaresNoDerivedAttributesOfItsOwn() {
        DurableStore first = durable.openStore(settings().build());
        tell(first);
        first.close();
        DurableStore reopened = durable.openStore(settings().noExpressionLanguage().build());
        SchemaDefinition other = SchemaDefinition.builder().entityType("Gadget", e -> e.attribute("name",
                ValueType.STRING)).build();
        SchemaDefinition derived = SchemaDefinition.builder().entityType("Widget", e -> e.attribute("name",
                ValueType.STRING).attribute("shout", ValueType.STRING, a -> a.derived("self.name"))).build();

        RegistrationResult gadgets = reopened.schema().register(new Origin("gadgets", "1.0.0"), other);
        RegistrationResult widgets = reopened.schema().register(new Origin("widgets", "1.0.0"), derived);

        assertThat(gadgets).as("the derived attributes of another origin do not stop it")
                .isInstanceOf(RegistrationResult.Applied.class);
        assertThat(widgets).isInstanceOf(RegistrationResult.Rejected.class);
        assertThat(((RegistrationResult.Rejected) widgets).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
            assertThat(problem.path()).isEqualTo("Widget.shout");
        });
    }

    // ------------------------------------------------------------------ a language that cannot compile them

    @Test
    void aStoreOpenedWithALanguageThatCannotCompileADerivationFailsTheWritesThatNeedIt() {
        DurableStore first = durable.openStore(settings().build());
        Told told = tell(first);
        first.close();
        ExpressionLanguage newer = new ExpressionLanguage() {
            @Override
            public String name() {
                return "newer";
            }

            @Override
            public CompiledCheck compile(String expression, Map<String, ValueType> attributes) {
                return values -> true;
            }

            @Override
            public CompiledDerivation compileDerivation(String expression, Map<String, ValueType> attributes,
                    ValueType result) throws ExpressionCompilationException {
                throw new ExpressionCompilationException("This version no longer reads currencies.", 1, 1);
            }
        };

        DurableStore reopened = durable.openStore(settings().expressionLanguage(newer).build());

        List<Problem> problems = ((CommitResult.Rejected) reopened.commit(setCurrency(told.a1(), "USD"))).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.DERIVATION_FAILED);
            assertThat(problem.path()).isEqualTo(told.a1().value() + ".label");
            assertThat(problem.message()).contains("label").contains("loaded")
                    .contains("This version no longer reads currencies.");
        });
        assertThat(reopened.commit(Transaction.by(PRINCIPAL).patch(told.alice(), from(4, 1),
                values("name", "Alice Smith")).build())).isInstanceOf(CommitResult.Committed.class);
        assertThat(label(reopened, told.a1(), 3, 31)).as("the stored values are read").isEqualTo(Value.of("A-1 EUR"));
    }
}
