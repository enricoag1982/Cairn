package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Derived attributes in durable stores, whichever kind the run of the tests is for: a directory store, or a RocksDB store
 * (see {@link TestBackend}). The derived values and the expressions of the schema survive a reopening, and nothing is
 * computed again when the store recovers (ADR-0022, design D4 of {@code add-derived-attributes}). The tests about the
 * files of directory stores are in {@code DurableDerivedTest}.
 */
class DerivedAttributesDurabilityTest {

    private static final SchemaDefinition MODEL = withDerivedAttribute(ReferenceModel.definition(), "Account", "label",
            ValueType.STRING, true, "self.number + ' ' + self.currency");

    @TempDir
    Path directory;

    private final List<DurableStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        opened.forEach(DurableStore::close);
    }

    private StoreSettings.Builder settings(TestClock clock) {
        return StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules());
    }

    private DurableStore open(StoreSettings settings) {
        DurableStore store = TestBackend.isRocksDb() ? TestBackend.openRocksDb(directory, settings)
                : Stores.durable(directory, settings);
        opened.add(store);
        return store;
    }

    private record Told(EntityId a1, EntityId alice) {
    }

    private static Told tell(DurableStore store, TestClock clock) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), MODEL))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        Receipt accounts = ReferenceStories.replay(store, clock, ReferenceStories.rd5Accounts(
                parties.temporaryIds().get(ReferenceStories.BETA), parties.temporaryIds().get(ReferenceStories.ACME)));
        return new Told((EntityId) accounts.temporaryIds().get(A1), (EntityId) parties.temporaryIds().get(ALICE));
    }

    private static Value label(DurableStore store, EntityId account) {
        return store.view(Coordinates.effectiveAt(on(3, 31))).entity(account).orElseThrow().values().get("label");
    }

    @Test
    void theDerivedValuesAreRecoveredAsTheyWereStoredWithoutComputingAgain() {
        TestClock clock = new TestClock();
        DurableStore first = open(settings(clock).build());
        Told told = tell(first, clock);
        first.close();
        StubLanguage language = new StubLanguage();

        DurableStore reopened = open(settings(clock).expressionLanguage(language).build());

        assertThat(label(reopened, told.a1())).isEqualTo(Value.of("A-1 EUR"));
        assertThat(language.evaluations()).as("recovery computes nothing").isZero();
        assertThat(language.derivations()).as("the recorded expression is compiled again").hasSize(1);
        clock.moveTo(on(4, 2));
        assertThat(reopened.commit(Transaction.by(PRINCIPAL).patch(told.a1(), from(4, 1), values("currency", "USD"))
                .build())).isInstanceOf(CommitResult.Committed.class);
        assertThat(language.evaluations()).as("the next write is computed").isEqualTo(1);
    }

    @Test
    void aStoreOpenedWithoutALanguageRejectsWritesToTypesWithDerivedAttributesAndWritesTheRest() {
        TestClock clock = new TestClock();
        DurableStore first = open(settings(clock).build());
        Told told = tell(first, clock);
        first.close();

        DurableStore reopened = open(settings(clock).noExpressionLanguage().build());
        clock.moveTo(on(4, 2));
        CommitResult setsCurrency = reopened.commit(Transaction.by(PRINCIPAL)
                .patch(told.a1(), from(4, 1), values("currency", "USD")).build());
        CommitResult renames = reopened.commit(Transaction.by(PRINCIPAL)
                .patch(told.alice(), from(4, 1), values("name", "Alice Smith")).build());

        assertThat(setsCurrency).isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) setsCurrency).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.EXPRESSIONS_UNAVAILABLE);
            assertThat(problem.path()).isEqualTo(told.a1().value());
        });
        assertThat(renames).isInstanceOf(CommitResult.Committed.class);
        assertThat(label(reopened, told.a1())).as("the stored values are read").isEqualTo(Value.of("A-1 EUR"));
    }

    @Test
    void theExpressionsOfTheSchemaSurviveAReopening() {
        TestClock clock = new TestClock();
        DurableStore first = open(settings(clock).build());
        tell(first, clock);
        first.close();

        DurableStore reopened = open(settings(clock).noExpressionLanguage().build());

        assertThat(reopened.schema().current().entityType("Account").orElseThrow().attributes())
                .filteredOn(attribute -> attribute.derived().isPresent()).singleElement().satisfies(attribute -> {
                    assertThat(attribute.name()).isEqualTo("label");
                    assertThat(attribute.required()).isTrue();
                    assertThat(attribute.derived()).hasValue("self.number + ' ' + self.currency");
                });
    }
}
